package com.rag.common.parser.impl;

import com.rag.common.client.MinerUClient;
import com.rag.common.exception.RagException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MinerU 文档解析器（PDF 默认解析器）。
 * <p>
 * <b>定位</b>：PDF 的默认解析器；亦可通过上传参数 {@code modelName=minerU} 强制覆盖任意文件类型。
 * </p>
 * <p>
 * <b>实现方式</b>：委托 {@link MinerUClient} 调用远程 MinerU 官方 API（mineru.net），
 * 由服务端完成版面分析、表格与公式还原，产出 Markdown 全文。本类仅负责
 * 「读取文件字节 → 调用客户端 → 组装 Document 与 metadata」三段薄适配逻辑。
 * </p>
 * <p>
 * <b>产出粒度</b>：整份文档产出<b>单个</b> {@link Document}，正文为 Markdown 全文，
 * 交由后续分块策略（text-model / hierarchical-model）切分。
 * </p>
 * <p>
 * <b>失败策略（不做隐式降级）</b>：MinerU 调用失败、轮询超时、结果为空时直接抛出异常，
 * 使上传明确失败而非静默产出低质量内容。异常信息包含 state / err_msg / batchId 便于定位；
 * 如需临时回退到原有解析链路，可改配置 {@code rag.parser.pdf-provider: tika-mixed}。
 * </p>
 * <p>
 * <b>参数设计</b>：构造器仅接收「客户端 + 待解析文件」两个参数——全部配置参数
 * （凭证 / 模型版本 / 语言 / 超时 / 轮询策略）已由 {@link MinerUClient} 持有的
 * {@code MinerUOptions} 承载，避免在每个文件 new 解析器时重复传递应用级配置。
 * </p>
 *
 * @author rag-tool
 * @since 1.0
 */
public class MinerUParser implements DocumentReader {

    private static final Logger log = LoggerFactory.getLogger(MinerUParser.class);

    /** 来源类型标记（写入 Document metadata.sourceType，便于检索溯源解析器） */
    public static final String SOURCE_TYPE = "mineru";

    /** MinerU 客户端（持有全部配置参数，构造期注入） */
    private final MinerUClient minerUClient;

    /** 待解析文件 */
    private final File file;

    /**
     * 构造器。
     *
     * @param minerUClient MinerU 客户端（已装配配置，可跨文件复用）
     * @param file         待解析文件
     */
    public MinerUParser(MinerUClient minerUClient, File file) {
        this.minerUClient = minerUClient;
        this.file = file;
    }

    /**
     * 解析文档为 Document 列表。
     * <p>
     * 读取文件字节后交由 MinerU 解析为 Markdown，组装为单个 Document；
     * metadata 写入 fileName / fileType / fileSize / sourcePath / sourceType。
     * </p>
     *
     * @return 含 Markdown 全文的 Document 列表（单个元素）
     * @throws RagException 读取文件失败、MinerU 调用失败（含超时/服务端解析失败）或结果为空
     */
    @Override
    public List<Document> get() {
        log.info("MinerU 解析器路由命中: file={}, size={} bytes", file.getName(), file.length());

        byte[] bytes;
        try {
            // 一次性读入文件字节（受上传侧 multipart 限制约束，最大 100MB）
            bytes = Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            throw new RagException("RAG_PARSE_MINERU_READ",
                    "读取文件失败: " + file.getName() + ", 原因: " + e.getMessage(), e);
        }

        String markdown;
        try {
            // 调用 MinerU（内部完成：申请链接 → PUT 上传 → 轮询 → 下载 zip 取 full.md）
            markdown = minerUClient.parseToMarkdown(file.getName(), bytes);
        } catch (Exception e) {
            // 失败不做隐式降级：明确抛出，并在消息中给出回退指引
            throw new RagException("RAG_PARSE_MINERU",
                    "MinerU 解析失败: " + file.getName() + ", 原因: " + e.getMessage()
                            + "；如需临时回退可改配置 rag.parser.pdf-provider=tika-mixed", e);
        }

        if (markdown == null || markdown.isBlank()) {
            // 兜底防御：避免「路由命中但产出空内容」静默污染向量库
            throw new RagException("RAG_PARSE_MINERU_EMPTY",
                    "MinerU 解析结果为空: " + file.getName());
        }

        String ext = getExtension(file.getName());
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("fileName", file.getName());
        metadata.put("fileType", ext);
        metadata.put("fileSize", file.length());
        metadata.put("sourcePath", file.getAbsolutePath());
        metadata.put("sourceType", SOURCE_TYPE);

        log.info("MinerU 解析完成: file={}, markdown 长度={}", file.getName(), markdown.length());
        return List.of(new Document(UUID.randomUUID().toString(), markdown, metadata));
    }

    /**
     * 提取文件扩展名（转小写，如 {@code pdf}）。
     *
     * @param fileName 文件名
     * @return 小写扩展名；无扩展名时返回空串
     */
    private String getExtension(String fileName) {
        int i = fileName.lastIndexOf('.');
        return i > 0 ? fileName.substring(i + 1).toLowerCase() : "";
    }
}
