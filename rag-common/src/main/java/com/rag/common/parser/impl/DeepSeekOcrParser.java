package com.rag.common.parser.impl;

import com.rag.common.exception.RagException;
import com.rag.common.client.OpenAiClient;
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
import java.util.Set;
import java.util.UUID;

/**
 * 基于 DeepSeek-OCR（OpenAI 兼容远程端点）的统一解析器。
 * <p>
 * 同时覆盖图片 OCR 与文档解析（PDF / Word / PPT / Excel / HTML 等），
 * 不引入任何本地渲染/解析库：文件字节流直接交由远程多模态模型渲染并识别。
 * <p>
 * 实现 Spring AI {@link DocumentReader} 接口，返回承载全文的 {@link Document}。
 * OCR 调用统一委托给 {@link OpenAiClient#ocr}。
 */
public class DeepSeekOcrParser implements DocumentReader {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekOcrParser.class);

    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "jpg", "jpeg", "png", "bmp",
            "pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx", "html", "htm");

    private static final Map<String, String> MIME_MAP = Map.ofEntries(
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("png", "image/png"),
            Map.entry("bmp", "image/bmp"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("html", "text/html"),
            Map.entry("htm", "text/html")
    );

    /** 统一 OpenAI 兼容客户端 */
    private final OpenAiClient openAiClient;
    /** OCR 配置：baseUrl / apiKey / model / timeoutMs / maxTokens */
    private final String ocrBaseUrl;
    private final String ocrApiKey;
    private final String ocrModel;
    private final long ocrTimeoutMs;
    /** OCR 输出 token 上限（<=0 时由 OpenAiClient 回落默认值） */
    private final int ocrMaxTokens;
    private final File file;

    public DeepSeekOcrParser(OpenAiClient openAiClient,
                             String ocrBaseUrl, String ocrApiKey, String ocrModel, long ocrTimeoutMs,
                             int ocrMaxTokens, File file) {
        this.openAiClient = openAiClient;
        this.ocrBaseUrl = ocrBaseUrl;
        this.ocrApiKey = ocrApiKey;
        this.ocrModel = ocrModel;
        this.ocrTimeoutMs = ocrTimeoutMs;
        this.ocrMaxTokens = ocrMaxTokens;
        this.file = file;
    }

    @Override
    public List<Document> get() {
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            String ext = getExtension(file.getName());
            String text = openAiClient.ocr(ocrBaseUrl, ocrApiKey, ocrModel, bytes, mimeOf(ext),
                    ocrTimeoutMs, ocrMaxTokens);
            if (text.isEmpty()) {
                log.warn("DeepSeek-OCR 未识别到文本: {}", file.getName());
            }
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("fileName", file.getName());
            metadata.put("fileType", ext);
            metadata.put("fileSize", file.length());
            metadata.put("sourcePath", file.getAbsolutePath());
            return List.of(new Document(UUID.randomUUID().toString(), text, metadata));
        } catch (IOException e) {
            throw new RagException("RAG_PARSE_OCR", "读取文件失败: " + e.getMessage(), e);
        }
    }

    private String mimeOf(String ext) {
        return MIME_MAP.getOrDefault(ext.toLowerCase(), "application/octet-stream");
    }

    private String getExtension(String fileName) {
        int i = fileName.lastIndexOf('.');
        return i > 0 ? fileName.substring(i + 1).toLowerCase() : "";
    }
}
