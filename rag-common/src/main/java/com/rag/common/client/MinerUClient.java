package com.rag.common.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * MinerU 官方 API v4 客户端（mineru.net API 精准解析）。
 * <p>
 * <b>职责边界</b>：MinerU 是独立部署的文档解析服务，使用自有端点（{@code /api/v4/**}），
 * <b>并非 OpenAI 兼容协议</b>，因此不复用 {@link OpenAiClient}。本类仅复用其 OkHttp 构建、
 * Jackson 序列化、非 2xx 读取响应体拼错误信息、异常统一包装的成熟风格，保持全项目一致。
 * </p>
 *
 * <h3>四段式调用链路</h3>
 * <ol>
 *   <li><b>申请上传链接</b>：{@code POST /api/v4/file-urls/batch}，返回 {@code batch_id} 与 {@code file_urls}</li>
 *   <li><b>上传文件</b>：{@code PUT file_urls[0]}，上传原始字节（<b>不设置 Content-Type</b>，官方要求）；
 *       上传完成后服务端自动提交解析任务，无需再调提交接口</li>
 *   <li><b>轮询状态</b>：{@code GET /api/v4/extract-results/batch/{batch_id}}，直到
 *       {@code state=done}（取 {@code full_zip_url}）或 {@code state=failed}（取 {@code err_msg} 抛错）</li>
 *   <li><b>下载并解压</b>：{@code GET full_zip_url}，用 {@link ZipInputStream} 提取包内 {@code full.md}</li>
 * </ol>
 *
 * <h3>设计说明</h3>
 * <ul>
 *   <li><b>参数封装</b>：全部配置参数由构造器注入的 {@link MinerUOptions} 承载，
 *       对外方法仅接收数据参数（fileName + bytes），避免 10+ 参数平铺导致的调用错位。</li>
 *   <li><b>失败不降级</b>：任一环节失败均抛出异常（不做隐式回退），异常消息包含
 *       state / err_msg / batch_id，便于定位；调用方可改配置 {@code rag.parser.pdf-provider=tika-mixed} 回退。</li>
 *   <li><b>日志安全</b>：打印 batch_id / trace_id / 轮询进度；<b>绝不打印 api-key 与文件二进制内容</b>。</li>
 * </ul>
 *
 * @author rag-tool
 * @since 1.0
 */
public class MinerUClient {

    private static final Logger log = LoggerFactory.getLogger(MinerUClient.class);

    // ==================== 端点常量 ====================

    /** 申请文件上传链接（批量） */
    private static final String PATH_FILE_URLS_BATCH = "/api/v4/file-urls/batch";
    /** 查询批量解析结果（轮询） */
    private static final String PATH_EXTRACT_RESULTS_BATCH = "/api/v4/extract-results/batch/";

    // ==================== 状态常量 ====================

    /** 解析完成 */
    private static final String STATE_DONE = "done";
    /** 解析失败 */
    private static final String STATE_FAILED = "failed";

    /** 结果包内 Markdown 文件名（zip 内可能带目录层级，故按结尾匹配） */
    private static final String RESULT_MARKDOWN_NAME = "full.md";

    /** 响应体截断长度（拼入异常消息与日志，避免超长内容淹没关键信息） */
    private static final int BODY_TRUNCATE_LEN = 500;
    /** zip 内单个条目最大解压字节数（50MB），防御性上限，避免异常 zip 撑爆内存 */
    private static final int MAX_ENTRY_BYTES = 50 * 1024 * 1024;

    /** MinerU 错误码 → 排查提示（用于异常消息中附加可读说明） */
    private static final Map<String, String> ERROR_CODE_HINTS = Map.ofEntries(
            Map.entry("A0202", "Token 错误：请检查 api-key 是否正确、是否带 Bearer 前缀"),
            Map.entry("A0211", "Token 过期：请在 mineru.net 更换新 Token"),
            Map.entry("-60004", "空文件：请上传有效文件"),
            Map.entry("-60005", "文件大小超出限制：最大支持 200MB"),
            Map.entry("-60006", "文件页数超过限制：最大支持 200 页，请拆分后重试"),
            Map.entry("-60009", "任务提交队列已满：请稍后重试"),
            Map.entry("-60010", "解析失败：请稍后重试"),
            Map.entry("-60012", "找不到任务：请确认 batch_id 有效且未删除"),
            Map.entry("-60018", "每日解析任务数量已达上限：请明日再试"));

    // ==================== 依赖与配置 ====================

    /** MinerU 配置参数快照（构造期注入一次，实例内复用） */
    private final MinerUOptions options;
    /** 通用模板客户端（每次请求按需派生独立超时的实例） */
    private final ObjectMapper objectMapper;

    /**
     * 构造器：注入配置参数快照。
     *
     * @param options MinerU 配置（baseUrl / apiKey / 解析参数 / 超时与轮询策略）
     */
    public MinerUClient(MinerUOptions options) {
        this.options = options;
        this.objectMapper = new ObjectMapper();
        log.info("MinerU 客户端初始化: {}", options);
    }

    // ==================== 对外主方法 ====================

    /**
     * 提交文件至 MinerU，同步等待解析完成后返回 Markdown 正文。
     * <p>
     * 本方法串联四段式链路并阻塞至结果就绪，总等待上限为
     * {@code pollIntervalMs × maxPollCount}（默认 5s × 120 = 10 分钟）。
     * </p>
     *
     * @param fileName 原始文件名（<b>必须带正确后缀</b>，MinerU 据此判定文件格式，如 {@code .pdf}）
     * @param bytes    文件字节内容
     * @return {@code full.md} 的 Markdown 全文（保证非空；为空时抛异常）
     * @throws IllegalStateException 凭证缺失 / 上传失败 / 轮询超时 / 解析失败 / 结果包异常
     */
    public String parseToMarkdown(String fileName, byte[] bytes) {
        // ===== 前置校验：文件与凭证 =====
        if (bytes == null || bytes.length == 0) {
            throw new IllegalStateException("MinerU 解析失败: 文件内容为空, file=" + fileName);
        }
        if (!options.hasUsableApiKey()) {
            throw new IllegalStateException(
                    "MinerU 凭证未配置: 请在 mineru.net「API 管理」页面创建 Token，"
                            + "并填入 application.public.yml 的 spring.ai.platform.models.mineru.api-key");
        }

        // ===== 步骤1：申请上传链接 =====
        UploadTicket ticket = requestUploadUrl(fileName);

        // ===== 步骤2：PUT 上传文件字节（不设 Content-Type） =====
        uploadFile(ticket.uploadUrl, bytes, fileName);

        // ===== 步骤3：轮询解析状态 =====
        ExtractResult result = pollUntilFinished(ticket.batchId, fileName);

        // ===== 步骤4：下载结果 zip 并提取 full.md =====
        String markdown = downloadMarkdown(result.fullZipUrl, fileName);
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalStateException("MinerU 解析结果中 " + RESULT_MARKDOWN_NAME
                    + " 内容为空, file=" + fileName + ", batchId=" + ticket.batchId);
        }
        log.info("MinerU 解析成功: file={}, batchId={}, markdown 长度={}",
                fileName, ticket.batchId, markdown.length());
        return markdown;
    }

    // ==================== 步骤1：申请上传链接 ====================

    /**
     * 步骤1：申请文件上传链接。
     *
     * @param fileName 原始文件名
     * @return 上传票据（batch_id + 上传 URL）
     */
    private UploadTicket requestUploadUrl(String fileName) {
        // 请求体：解析参数 + 单文件清单（files[i] 与返回的 file_urls[i] 按下标一一对应）
        Map<String, Object> fileItem = new LinkedHashMap<>();
        fileItem.put("name", fileName);

        Map<String, Object> reqBody = new LinkedHashMap<>();
        reqBody.put("enable_formula", options.isEnableFormula());
        reqBody.put("enable_table", options.isEnableTable());
        reqBody.put("language", options.getLanguage());
        reqBody.put("model_version", options.getModelVersion());
        reqBody.put("files", List.of(fileItem));

        String body = postJson(PATH_FILE_URLS_BATCH, reqBody, "申请上传链接");
        BatchUploadResponse resp = readJson(body, BatchUploadResponse.class, "申请上传链接");

        if (resp.code != 0 || resp.data == null
                || resp.data.batchId == null || resp.data.fileUrls == null || resp.data.fileUrls.isEmpty()) {
            throw new IllegalStateException("MinerU 申请上传链接失败: code=" + resp.code
                    + ", msg=" + resp.msg + ", traceId=" + resp.traceId
                    + ", 响应=" + truncate(body, BODY_TRUNCATE_LEN));
        }
        log.info("MinerU 申请上传链接成功: batchId={}, traceId={}", resp.data.batchId, resp.traceId);
        return new UploadTicket(resp.data.batchId, resp.data.fileUrls.get(0), resp.traceId);
    }

    // ==================== 步骤2：PUT 上传文件 ====================

    /**
     * 步骤2：将文件字节 PUT 上传到预签名链接。
     * <p>
     * <b>注意</b>：官方明确要求上传时<b>不设置 Content-Type 头</b>，
     * 因此此处仅提交原始字节流，由 OkHttp 自动携带 Content-Length。
     * </p>
     *
     * @param uploadUrl 预签名上传 URL
     * @param bytes     文件字节内容
     * @param fileName  文件名（仅用于日志）
     */
    private void uploadFile(String uploadUrl, byte[] bytes, String fileName) {
        // body 的 contentType 传 null → 不产生 Content-Type 头
        RequestBody body = RequestBody.create(bytes, (MediaType) null);
        Request request = new Request.Builder().url(uploadUrl).put(body).build();

        try (Response resp = newClient().newCall(request).execute()) {
            if (!resp.isSuccessful()) {
                throw new IllegalStateException("MinerU 上传文件失败: HTTP " + resp.code()
                        + ", file=" + fileName + ", fileSize=" + bytes.length
                        + ", 响应=" + truncate(readBodySafely(resp), BODY_TRUNCATE_LEN));
            }
            log.info("MinerU 文件上传成功: file={}, size={} bytes", fileName, bytes.length);
        } catch (IOException e) {
            throw new IllegalStateException("MinerU 上传文件网络异常: file=" + fileName
                    + ", 原因=" + e.getMessage(), e);
        }
    }

    // ==================== 步骤3：轮询解析状态 ====================

    /**
     * 步骤3：轮询批量解析结果，直到状态为 done（可下载）或 failed（抛错）。
     * <p>
     * 中间态（waiting-file / pending / running / converting）继续等待，
     * 每轮 sleep {@code pollIntervalMs}；超过 {@code maxPollCount} 轮时抛超时异常。
     * </p>
     *
     * @param batchId  批量任务 ID
     * @param fileName 文件名（仅用于日志）
     * @return 解析结果（含 full_zip_url）
     */
    private ExtractResult pollUntilFinished(String batchId, String fileName) {
        int maxCount = Math.max(1, options.getMaxPollCount());
        long interval = Math.max(0L, options.getPollIntervalMs());
        String lastState = null;

        for (int i = 1; i <= maxCount; i++) {
            String body = getJson(PATH_EXTRACT_RESULTS_BATCH + batchId, "查询解析结果");
            BatchResultResponse resp = readJson(body, BatchResultResponse.class, "查询解析结果");

            if (resp.code != 0 || resp.data == null
                    || resp.data.extractResult == null || resp.data.extractResult.isEmpty()) {
                throw new IllegalStateException("MinerU 查询解析结果失败: code=" + resp.code
                        + ", msg=" + resp.msg + ", traceId=" + resp.traceId
                        + ", batchId=" + batchId + ", 响应=" + truncate(body, BODY_TRUNCATE_LEN));
            }

            ExtractResult item = resp.data.extractResult.get(0);
            lastState = item.state;

            if (STATE_DONE.equalsIgnoreCase(item.state)) {
                if (item.fullZipUrl == null || item.fullZipUrl.isBlank()) {
                    throw new IllegalStateException("MinerU 解析完成但未返回结果包地址: file=" + fileName
                            + ", batchId=" + batchId + ", traceId=" + resp.traceId);
                }
                log.info("MinerU 解析完成: file={}, batchId={}, 轮询次数={}", fileName, batchId, i);
                return item;
            }

            if (STATE_FAILED.equalsIgnoreCase(item.state)) {
                throw new IllegalStateException("MinerU 解析失败: file=" + fileName
                        + ", batchId=" + batchId + ", traceId=" + resp.traceId
                        + ", errMsg=" + item.errMsg + hintOf(item.errMsg));
            }

            // 中间态：记录进度并等待下一轮
            log.debug("MinerU 解析中({}/{}): file={}, state={}, 进度={}/{}",
                    i, maxCount, fileName, item.state,
                    item.extractProgress == null ? "-" : item.extractProgress.extractedPages,
                    item.extractProgress == null ? "-" : item.extractProgress.totalPages);
            sleepQuietly(interval);
        }

        // 轮询次数耗尽：明确报超时，不做隐式降级
        throw new IllegalStateException("MinerU 解析超时: file=" + fileName + ", batchId=" + batchId
                + ", 最后状态=" + lastState
                + ", 已等待=" + (interval * maxCount / 1000) + "s"
                + ", 可通过 rag.parser 下 mineru 的 max-poll-count / poll-interval-ms 调整等待上限");
    }

    // ==================== 步骤4：下载结果 zip 并提取 Markdown ====================

    /**
     * 步骤4：下载结果压缩包并提取 {@code full.md}。
     * <p>
     * zip 内可能带目录层级（如 {@code xxx/full.md}），故按「条目名以 full.md 结尾」匹配；
     * 解压单个条目后立即返回，不做整包解压，避免内存放大。
     * </p>
     *
     * @param zipUrl   结果包下载地址
     * @param fileName 文件名（仅用于日志）
     * @return Markdown 文本；包内未找到 full.md 时返回 null
     */
    private String downloadMarkdown(String zipUrl, String fileName) {
        Request request = new Request.Builder().url(zipUrl).get().build();
        try (Response resp = newClient().newCall(request).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IllegalStateException("MinerU 下载结果包失败: HTTP " + resp.code()
                        + ", file=" + fileName + ", 响应=" + truncate(readBodySafely(resp), BODY_TRUNCATE_LEN));
            }
            try (InputStream in = resp.body().byteStream();
                 ZipInputStream zis = new ZipInputStream(in)) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    // 按结尾匹配：兼容 zip 内的目录层级与可能的前缀
                    if (!entry.isDirectory() && entry.getName().endsWith(RESULT_MARKDOWN_NAME)) {
                        String markdown = readEntry(zis, entry.getName());
                        log.info("MinerU 结果包已提取: file={}, zipEntry={}, 长度={}",
                                fileName, entry.getName(), markdown.length());
                        return markdown;
                    }
                }
            }
            log.warn("MinerU 结果包内未找到 {}: file={}", RESULT_MARKDOWN_NAME, fileName);
            return null;
        } catch (IOException e) {
            throw new IllegalStateException("MinerU 下载结果包异常: file=" + fileName
                    + ", 原因=" + e.getMessage(), e);
        }
    }

    /**
     * 读取 zip 中单个条目的文本内容（UTF-8）。
     *
     * @param zis       zip 输入流（当前已定位到该条目）
     * @param entryName 条目名（仅用于日志与超限报错）
     * @return 条目文本
     * @throws IOException 读取失败或条目超过防御性大小上限
     */
    private String readEntry(ZipInputStream zis, String entryName) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int len;
        while ((len = zis.read(chunk)) != -1) {
            if (buffer.size() + len > MAX_ENTRY_BYTES) {
                throw new IOException("zip 条目超过大小上限 " + MAX_ENTRY_BYTES + " 字节: " + entryName);
            }
            buffer.write(chunk, 0, len);
        }
        return buffer.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    // ==================== HTTP 基础设施 ====================

    /**
     * 创建带配置超时的 OkHttp 客户端。
     * <p>每次调用新建实例：MinerU 解析耗时长，需独立超时控制，
     * 且连接池复用价值低于超时隔离价值（轮询间隔本身不占用连接）。</p>
     *
     * @return OkHttp 客户端
     */
    private OkHttpClient newClient() {
        long timeout = options.getTimeoutMs() > 0 ? options.getTimeoutMs() : 60_000L;
        return new OkHttpClient.Builder()
                .connectTimeout(timeout, TimeUnit.MILLISECONDS)
                .readTimeout(timeout, TimeUnit.MILLISECONDS)
                .writeTimeout(timeout, TimeUnit.MILLISECONDS)
                .build();
    }

    /**
     * 发送 JSON POST 请求（带 Bearer 鉴权）。
     *
     * @param path        端点路径
     * @param reqBody     请求体对象
     * @param actionDesc  动作描述（用于异常消息）
     * @return 响应体文本
     */
    private String postJson(String path, Object reqBody, String actionDesc) {
        try {
            String json = objectMapper.writeValueAsString(reqBody);
            RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));
            Request request = new Request.Builder()
                    .url(buildUrl(path))
                    .post(body)
                    .addHeader("Authorization", "Bearer " + options.getApiKey())
                    .addHeader("Accept", "*/*")
                    .build();
            return execute(request, actionDesc);
        } catch (IOException e) {
            throw new IllegalStateException("MinerU " + actionDesc + "请求构造失败: " + e.getMessage(), e);
        }
    }

    /**
     * 发送 GET 请求（带 Bearer 鉴权）。
     *
     * @param path        端点路径
     * @param actionDesc  动作描述（用于异常消息）
     * @return 响应体文本
     */
    private String getJson(String path, String actionDesc) {
        Request request = new Request.Builder()
                .url(buildUrl(path))
                .get()
                .addHeader("Authorization", "Bearer " + options.getApiKey())
                .addHeader("Accept", "*/*")
                .build();
        return execute(request, actionDesc);
    }

    /**
     * 执行请求并按统一规则处理响应。
     *
     * @param request    OkHttp 请求
     * @param actionDesc 动作描述
     * @return 响应体文本
     */
    private String execute(Request request, String actionDesc) {
        try (Response resp = newClient().newCall(request).execute()) {
            String respBody = readBodySafely(resp);
            if (!resp.isSuccessful()) {
                // 非 2xx 必须把响应体带出来，否则真实的失败原因（错误码/鉴权问题）会被丢弃
                throw new IllegalStateException("MinerU " + actionDesc + "失败: HTTP " + resp.code()
                        + ", 响应=" + truncate(respBody, BODY_TRUNCATE_LEN));
            }
            return respBody;
        } catch (IOException e) {
            throw new IllegalStateException("MinerU " + actionDesc + "网络异常: " + e.getMessage(), e);
        }
    }

    /**
     * 安全读取响应体文本（响应体不可读时返回空串，不抛异常）。
     *
     * @param resp 响应
     * @return 响应体文本
     */
    private String readBodySafely(Response resp) {
        try {
            ResponseBody body = resp.body();
            return body == null ? "" : body.string();
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * 反序列化 JSON 响应体。
     *
     * @param body       响应体文本
     * @param clazz      目标类型
     * @param actionDesc 动作描述
     * @param <T>        目标类型
     * @return 反序列化结果
     */
    private <T> T readJson(String body, Class<T> clazz, String actionDesc) {
        try {
            return objectMapper.readValue(body, clazz);
        } catch (Exception e) {
            throw new IllegalStateException("MinerU " + actionDesc + "响应解析失败: " + e.getMessage()
                    + ", 响应=" + truncate(body, BODY_TRUNCATE_LEN), e);
        }
    }

    /**
     * 拼接完整请求 URL（处理 baseUrl 尾部斜杠与 path 头部斜杠的重复）。
     *
     * @param path 端点路径（以 / 开头）
     * @return 完整 URL
     */
    private String buildUrl(String path) {
        String base = options.getBaseUrl() == null ? "" : options.getBaseUrl().trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + path;
    }

    /**
     * 从 err_msg 中提取 MinerU 错误码并附加可读提示。
     *
     * @param errMsg 服务端返回的失败原因
     * @return 附加提示（无匹配时返回空串）
     */
    private String hintOf(String errMsg) {
        if (errMsg == null) {
            return "";
        }
        for (Map.Entry<String, String> e : ERROR_CODE_HINTS.entrySet()) {
            if (errMsg.contains(e.getKey())) {
                return "（" + e.getValue() + "）";
            }
        }
        return "";
    }

    /**
     * 截断过长文本，避免超长响应体淹没日志与异常消息。
     *
     * @param s   原文本
     * @param max 最大保留长度
     * @return 截断后的文本
     */
    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...(truncated)";
    }

    /**
     * 静默休眠（被中断时恢复中断标记并抛出运行时异常，不吞掉中断信号）。
     *
     * @param millis 休眠毫秒数
     */
    private void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MinerU 轮询等待被中断: " + e.getMessage(), e);
        }
    }

    // ==================== 内部值对象 ====================

    /**
     * 上传票据：申请上传链接后的返回信息。
     *
     * @param batchId   批量任务 ID（用于轮询结果）
     * @param uploadUrl 预签名上传 URL（取 file_urls[0]）
     * @param traceId   请求追踪 ID（用于向 MinerU 侧追溯）
     */
    private record UploadTicket(String batchId, String uploadUrl, String traceId) {
    }

    /**
     * 单文件解析结果（轮询响应中的数据项）。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ExtractResult {
        /** 文件名 */
        @JsonProperty("file_name")
        public String fileName;

        /** 任务状态：done / waiting-file / pending / running / failed / converting */
        public String state;

        /** 解析失败原因（state=failed 时有效） */
        @JsonProperty("err_msg")
        public String errMsg;

        /** 解析结果 zip 包下载地址（state=done 时有效） */
        @JsonProperty("full_zip_url")
        public String fullZipUrl;

        /** 解析进度（state=running 时有效） */
        @JsonProperty("extract_progress")
        public ExtractProgress extractProgress;
    }

    /**
     * 解析进度信息。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ExtractProgress {
        /** 已解析页数 */
        @JsonProperty("extracted_pages")
        public Integer extractedPages;

        /** 总页数 */
        @JsonProperty("total_pages")
        public Integer totalPages;

        /** 解析开始时间 */
        @JsonProperty("start_time")
        public String startTime;
    }

    /** 申请上传链接响应体 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class BatchUploadResponse {
        /** 状态码，0 表示成功 */
        public int code;
        /** 处理信息 */
        public String msg;
        /** 请求追踪 ID */
        @JsonProperty("trace_id")
        public String traceId;
        /** 业务数据 */
        public BatchUploadData data;
    }

    /** 申请上传链接响应数据 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class BatchUploadData {
        /** 批量任务 ID */
        @JsonProperty("batch_id")
        public String batchId;
        /** 文件上传链接数组（与请求 files 按下标一一对应） */
        @JsonProperty("file_urls")
        public List<String> fileUrls = new ArrayList<>();
    }

    /** 查询解析结果响应体 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class BatchResultResponse {
        /** 状态码，0 表示成功 */
        public int code;
        /** 处理信息 */
        public String msg;
        /** 请求追踪 ID */
        @JsonProperty("trace_id")
        public String traceId;
        /** 业务数据 */
        public BatchResultData data;
    }

    /** 查询解析结果响应数据 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class BatchResultData {
        /** 批量任务 ID */
        @JsonProperty("batch_id")
        public String batchId;
        /** 解析结果列表（单文件场景取第 0 项） */
        @JsonProperty("extract_result")
        public List<ExtractResult> extractResult = new ArrayList<>();
    }
}
