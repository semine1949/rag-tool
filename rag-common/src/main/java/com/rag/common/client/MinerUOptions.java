package com.rag.common.client;

/**
 * MinerU 解析服务配置参数快照（值对象）。
 * <p>
 * <b>存在意义（参数封装）</b>：MinerU 调用涉及 9 项配置参数（凭证 / 模型版本 / 语言 /
 * 识别开关 / 超时 / 轮询策略），若直接在 {@link MinerUClient} 的方法与构造器上平铺，
 * 会出现「10+ 个参数」且相邻参数类型相同（均为 String / boolean / long），
 * 调用点极易错位且编译器无法发现。故此处一次性封装为值对象，
 * 使方法签名收敛为「数据参数」（fileName + bytes）两项。
 * </p>
 * <p>
 * <b>生命周期</b>：本对象为「应用级」配置——由 {@code RagCoreConfig} 从
 * {@code application.public.yml} 的 {@code spring.ai.platform.models.mineru} 段读取后
 * 构造一次，注入 {@link MinerUClient} 持有并全程复用，不在每次解析时重建。
 * </p>
 * <p>
 * <b>线程安全</b>：所有字段为不可变的基本类型/String（仅提供 getter，无 setter），
 * 对象发布后即只读，可安全地被多个解析线程共享。
 * </p>
 *
 * @author rag-tool
 * @since 1.0
 */
public class MinerUOptions {

    // ==================== 连接与凭证 ====================

    /** API 基础地址（如 {@code https://mineru.net}），官方精准解析 API 的根地址 */
    private final String baseUrl;

    /** Bearer Token（在 mineru.net「API 管理」页面创建） */
    private final String apiKey;

    // ==================== 解析参数（对应 file-urls/batch 请求体） ====================

    /** 模型版本：{@code pipeline} / {@code vlm} / {@code MinerU-HTML}，vlm 版面与公式还原更好 */
    private final String modelVersion;

    /** 文档语言（如 {@code ch} / {@code en}），仅对 pipeline、vlm 有效 */
    private final String language;

    /** 是否开启公式识别（仅对 pipeline、vlm 有效） */
    private final boolean enableFormula;

    /** 是否开启表格识别（仅对 pipeline、vlm 有效） */
    private final boolean enableTable;

    // ==================== 超时与轮询策略 ====================

    /** 单次 HTTP 请求超时（毫秒），覆盖申请链接 / PUT 上传 / 单次轮询 / 下载 zip */
    private final long timeoutMs;

    /** 轮询间隔（毫秒），两次查询解析状态之间的等待时间 */
    private final long pollIntervalMs;

    /** 最大轮询次数；与 {@link #pollIntervalMs} 共同构成总等待上限，防止上传线程无限阻塞 */
    private final int maxPollCount;

    /**
     * 全参构造器（由配置装配层调用，业务代码请直接复用已装配的实例）。
     *
     * @param baseUrl        API 基础地址
     * @param apiKey         Bearer Token
     * @param modelVersion   模型版本（pipeline / vlm / MinerU-HTML）
     * @param language       文档语言
     * @param enableFormula  是否开启公式识别
     * @param enableTable    是否开启表格识别
     * @param timeoutMs      单次 HTTP 请求超时（毫秒）
     * @param pollIntervalMs 轮询间隔（毫秒）
     * @param maxPollCount   最大轮询次数
     */
    public MinerUOptions(String baseUrl, String apiKey, String modelVersion, String language,
                         boolean enableFormula, boolean enableTable,
                         long timeoutMs, long pollIntervalMs, int maxPollCount) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.modelVersion = modelVersion;
        this.language = language;
        this.enableFormula = enableFormula;
        this.enableTable = enableTable;
        this.timeoutMs = timeoutMs;
        this.pollIntervalMs = pollIntervalMs;
        this.maxPollCount = maxPollCount;
    }

    /**
     * 校验凭证是否可用。
     * <p>api-key 为空或仍为配置占位符时返回 false，调用方据此提前抛出明确异常，
     * 避免把无效凭证发往 MinerU 换取一个含义模糊的 HTTP 401。</p>
     *
     * @return true=凭证看起来可用
     */
    public boolean hasUsableApiKey() {
        return apiKey != null && !apiKey.isBlank() && !apiKey.startsWith("your-");
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public String getLanguage() {
        return language;
    }

    public boolean isEnableFormula() {
        return enableFormula;
    }

    public boolean isEnableTable() {
        return enableTable;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public int getMaxPollCount() {
        return maxPollCount;
    }

    /**
     * 输出可安全打印的配置摘要（**不含 api-key**，避免密钥进入日志）。
     *
     * @return 配置摘要字符串
     */
    @Override
    public String toString() {
        return "MinerUOptions{baseUrl=" + baseUrl
                + ", apiKey=" + (hasUsableApiKey() ? "***" : "<未配置>")
                + ", modelVersion=" + modelVersion
                + ", language=" + language
                + ", enableFormula=" + enableFormula
                + ", enableTable=" + enableTable
                + ", timeoutMs=" + timeoutMs
                + ", pollIntervalMs=" + pollIntervalMs
                + ", maxPollCount=" + maxPollCount
                + '}';
    }
}
