package com.rag.common.parser.impl;

import com.rag.common.exception.RagException;
import com.rag.common.client.OpenAiClient;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import java.io.*;
import java.nio.file.Files;
import java.util.*;

/**
 * 文本+图片混合文档解析器（Tika 文本提取 + 内嵌图片多模态 OCR + 扫描件 PDF 逐页 OCR）。
 * <p>
 * 适用文件类型：PDF / DOC / DOCX / PPT / PPTX 等常包含内嵌图片的 Office 文档。
 * <p>
 * 解析流程（入口按「文本层是否为空」二分分流，即策略 A）：
 * <ol>
 *     <li>通过 Apache Tika {@link AutoDetectParser} 提取文本层，并同步截获内嵌图片</li>
 *     <li><b>场景 1（文本层非空）</b>：1 个文本 Document + 每个内嵌图片 1 个 OCR Document</li>
 *     <li><b>场景 2（文本层为空）</b>：
 *         <ul>
 *             <li>扩展名为 pdf → {@link PdfPageRenderer} 逐页渲染后逐页 OCR，每页 1 个 Document</li>
 *             <li>其他类型（Office 等）→ 回退整份文档单次 OCR</li>
 *         </ul>
 *     </li>
 * </ol>
 * <p>
 * <b>已知边界</b>：策略 A 只看「整份文档文本层是否为空」。若 PDF 前若干页有文本层、后续页为扫描图，
 * 会判定为场景 1 并只处理文本层与内嵌图，扫描页不会被逐页 OCR 覆盖（二分法固有边界，本轮不做页级判定）。
 * <p>
 * 实现 Spring AI {@link DocumentReader} 接口。
 */
public class TikaOcrMixedParser implements DocumentReader {

    private static final Logger log = LoggerFactory.getLogger(TikaOcrMixedParser.class);

    /** 统一的 PDF 逐页 OCR 内容类型标记 */
    private static final String PDF_PAGE_OCR = "pdf_page_ocr";

    /**
     * OCR 输出被视为「有实际正文」的最小有效字符数。
     * <p>
     * 剥离标点/括号/空白后剩余字符数低于此值时，判定为模型幻觉噪声（如 {@code }}、{@code []}）并丢弃。
     * 取 5 的理由：正常单页 OCR 正文远大于 5 字；而括号幻觉剥离后通常为 0~1 字，阈值 5 可安全区分二者，
     * 同时留出容错余量（避免把「答案：D」这类极短但有效的内容误杀）。
     * </p>
     */
    private static final int MIN_MEANINGFUL_OCR_CHARS = 5;

    private static final Map<String, String> EXT_MIME_MAP = Map.of(
            "pdf", "application/pdf",
            "doc", "application/msword",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "ppt", "application/vnd.ms-powerpoint",
            "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    );

    private final File file;
    /** 统一 OpenAI 兼容客户端 */
    private final OpenAiClient openAiClient;
    /** OCR 配置参数 */
    private final String ocrBaseUrl;
    private final String ocrApiKey;
    private final String ocrModel;
    private final long ocrTimeoutMs;
    /** 通用 OCR 输出 token 上限（内嵌图 / 整份回退 OCR 使用） */
    private final int ocrMaxTokens;
    /** 扫描件 PDF 逐页 OCR 的页输出 token 上限（单页文字密集，通常大于通用值） */
    private final int ocrPageMaxTokens;

    public TikaOcrMixedParser(OpenAiClient openAiClient,
                              String ocrBaseUrl, String ocrApiKey, String ocrModel,
                              long ocrTimeoutMs, int ocrMaxTokens, int ocrPageMaxTokens,
                              File file) {
        this.openAiClient = openAiClient;
        this.ocrBaseUrl = ocrBaseUrl;
        this.ocrApiKey = ocrApiKey;
        this.ocrModel = ocrModel;
        this.ocrTimeoutMs = ocrTimeoutMs;
        this.ocrMaxTokens = ocrMaxTokens;
        this.ocrPageMaxTokens = ocrPageMaxTokens;
        this.file = file;
    }

    @Override
    public List<Document> get() {
        List<Document> documents = new ArrayList<>();

        try (InputStream is = Files.newInputStream(file.toPath())) {
            // 1. Tika 提取文本 + 同步捕获内嵌图片
            List<ImageData> images = new ArrayList<>();
            Parser parser = new AutoDetectParser();
            BodyContentHandler textHandler = new BodyContentHandler(-1); // 不限制长度
            Metadata metadata = new Metadata();
            metadata.set("resourceName", file.getName());

            ParseContext context = new ParseContext();
            context.set(Parser.class, parser);
            context.set(EmbeddedDocumentExtractor.class, new ImageCapturingExtractor(context, images));

            parser.parse(is, textHandler, metadata, context);

            String extractedText = textHandler.toString().trim();

            // 2. 策略 A 分流：文本层非空 → 场景 1（原逻辑不变）；文本层为空 → 场景 2（扫描件）

            // ---------- 场景 1：文本层非空，走 Tika 文本 + 内嵌图片 OCR（零回归） ----------
            if (!extractedText.isEmpty()) {
                Map<String, Object> textMeta = buildBaseMetadata("text");
                textMeta.put("contentType", "text");
                documents.add(new Document(UUID.randomUUID().toString(), extractedText, textMeta));
                log.info("Tika 文本提取成功: {}, size={}", file.getName(), extractedText.length());

                // 对内嵌图片逐张做多模态 OCR
                for (int i = 0; i < images.size(); i++) {
                    ImageData img = images.get(i);
                    try {
                        String ocrText = openAiClient.ocr(ocrBaseUrl, ocrApiKey, ocrModel,
                                img.bytes, img.mimeType, ocrTimeoutMs, ocrMaxTokens);
                        if (!hasMeaningfulOcrText(ocrText)) {
                            log.warn("内嵌图片 OCR 无有效内容 (index={}, file={}): {}", i, file.getName(), ocrText);
                            continue;
                        }
                        {
                            Map<String, Object> imgMeta = buildBaseMetadata("image_ocr");
                            imgMeta.put("contentType", "image_ocr");
                            imgMeta.put("imageIndex", i);
                            imgMeta.put("imageMimeType", img.mimeType);
                            documents.add(new Document(UUID.randomUUID().toString(), ocrText, imgMeta));
                            log.info("内嵌图片 OCR (index={}) 成功: {}", i, file.getName());
                        }
                    } catch (Exception e) {
                        log.warn("内嵌图片 OCR 失败 (index={}, file={}): {}", i, file.getName(), e.getMessage());
                    }
                }
                return documents;
            }

            // ---------- 场景 2：文本层为空（扫描件），按扩展名细分 ----------
            log.warn("Tika 未提取到文本层，判定为扫描件: {}", file.getName());
            String ext = getExtension(file.getName());

            if ("pdf".equals(ext)) {
                // PDF：逐页渲染 + 逐页 OCR（不再处理 Tika 抽到的内嵌图，避免重复识别与切片碎片化）
                parseScannedPdf(documents);
            } else {
                // 非 PDF（Office 等无通用分页渲染 API）：保持原整份文档 OCR 回退
                fallbackWholeFileOcr(documents, ext);
            }

        } catch (IOException | SAXException | TikaException e) {
            throw new RagException("RAG_PARSE_MIXED", "混合解析失败: " + e.getMessage(), e);
        }

        return documents;
    }

    /**
     * 扫描件 PDF 逐页渲染 + 逐页 OCR。
     * <p>
     * 每页产出 1 个 Document，metadata 标注 {@code contentType=pdf_page_ocr} 与页序信息；
     * 单页 OCR 失败仅 WARN 跳过，不影响整份解析。
     */
    private void parseScannedPdf(List<Document> documents) {
        PdfPageRenderer renderer = new PdfPageRenderer();
        List<PdfPageRenderer.PageImage> pages = renderer.render(file);
        if (pages.isEmpty()) {
            log.error("扫描件 PDF 无任何页面渲染成功: {}", file.getName());
            return;
        }

        int totalPages = pages.size();
        for (PdfPageRenderer.PageImage page : pages) {
            try {
                String ocrText = openAiClient.ocr(ocrBaseUrl, ocrApiKey, ocrModel,
                        page.getPngBytes(), "image/png", ocrTimeoutMs, ocrPageMaxTokens);
                if (!hasMeaningfulOcrText(ocrText)) {
                    log.warn("扫描件 PDF 页面 OCR 无有效内容 (page={}, file={}): {}",
                            page.getPageIndex(), file.getName(), ocrText);
                    continue;
                }
                Map<String, Object> pageMeta = buildBaseMetadata(PDF_PAGE_OCR);
                pageMeta.put("contentType", PDF_PAGE_OCR);
                pageMeta.put("pageIndex", page.getPageIndex());
                pageMeta.put("pageCount", totalPages);
                pageMeta.put("renderDpi", PdfPageRenderer.DEFAULT_RENDER_DPI);
                documents.add(new Document(UUID.randomUUID().toString(), ocrText, pageMeta));
                log.info("扫描件 PDF 逐页 OCR 成功 (page={}/{}): {}", page.getPageIndex() + 1, totalPages, file.getName());
            } catch (Exception e) {
                log.warn("扫描件 PDF 页面 OCR 失败并跳过 (page={}, file={}): {}",
                        page.getPageIndex(), file.getName(), e.getMessage());
            }
        }

        if (documents.isEmpty()) {
            log.error("扫描件 PDF 逐页 OCR 也无产出: {}", file.getName());
        }
    }

    /**
     * 整份文档单次 OCR 回退（非 PDF 扫描件，如纯图片型 Office 文档）。
     */
    private void fallbackWholeFileOcr(List<Document> documents, String ext) {
        try {
            String mime = EXT_MIME_MAP.getOrDefault(ext, "application/octet-stream");
            byte[] fileBytes = Files.readAllBytes(file.toPath());
            String ocrText = openAiClient.ocr(ocrBaseUrl, ocrApiKey, ocrModel,
                    fileBytes, mime, ocrTimeoutMs, ocrMaxTokens);
            if (hasMeaningfulOcrText(ocrText)) {
                Map<String, Object> fallbackMeta = buildBaseMetadata("fallback_ocr");
                fallbackMeta.put("contentType", "fallback_ocr");
                documents.add(new Document(UUID.randomUUID().toString(), ocrText, fallbackMeta));
                log.info("回退全量OCR成功: {}, size={}", file.getName(), ocrText.length());
            } else {
                log.error("回退全量OCR也无产出: {}", file.getName());
            }
        } catch (IOException e) {
            log.error("回退全量OCR读取文件失败 (file={}): {}", file.getName(), e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // 元数据构建
    // -------------------------------------------------------------------------

    private Map<String, Object> buildBaseMetadata(String sourceType) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("fileName", file.getName());
        meta.put("fileType", getExtension(file.getName()));
        meta.put("fileSize", file.length());
        meta.put("sourcePath", file.getAbsolutePath());
        meta.put("sourceType", sourceType);
        return meta;
    }

    private String getExtension(String fileName) {
        int i = fileName.lastIndexOf('.');
        return i > 0 ? fileName.substring(i + 1).toLowerCase() : "";
    }

    /**
     * 判断 OCR 输出是否为「有实际意义的正文」。
     * <p>
     * 背景：DeepSeek-OCR 等模型在输入未被正确编码、或图片信息量过低时，不会返回空串，
     * 而是输出 {@code }}、{@code }}]}}]}、{@code []}、{@code {}} 之类的<b>括号幻觉</b>占位内容。
     * 仅用 {@code isEmpty()} 判空会让这些噪声被当成有效文本入库，污染后续切片与向量化。
     * </p>
     * 判定策略：剥离所有标点、括号、转义符与空白后，剩余字符数 &ge; {@value #MIN_MEANINGFUL_OCR_CHARS} 才视为有效。
     *
     * @param ocrText OCR 原始输出（可为 null）
     * @return true=包含实际正文内容；false=空或纯符号噪声
     */
    private boolean hasMeaningfulOcrText(String ocrText) {
        if (ocrText == null || ocrText.isBlank()) {
            return false;
        }
        // 剥离空白、各种括号、转义符与常见标点，仅保留可能承载语义的字符
        String meaningful = ocrText.replaceAll("[\\s\\{\\}\\[\\]\\(\\)<>|_\\-=+*·•\\\\,;:.\"'`~^/、，。：；！？…—－]", "");
        return meaningful.length() >= MIN_MEANINGFUL_OCR_CHARS;
    }

    // -------------------------------------------------------------------------
    // 内嵌图片数据结构
    // -------------------------------------------------------------------------

    private static class ImageData {
        final byte[] bytes;
        final String mimeType;

        ImageData(byte[] bytes, String mimeType) {
            this.bytes = bytes;
            this.mimeType = mimeType;
        }
    }

    // -------------------------------------------------------------------------
    // Tika EmbeddedDocumentExtractor：截获内嵌图片字节
    // -------------------------------------------------------------------------

    /**
     * 扩展 Tika 内嵌文档提取器，只捕获 image/* 类型的嵌入资源。
     * 非图片嵌入资源（字体、其他文档等）跳过不处理。
     */
    private static class ImageCapturingExtractor implements EmbeddedDocumentExtractor {

        private final List<ImageData> capturedImages;

        ImageCapturingExtractor(ParseContext context, List<ImageData> capturedImages) {
            this.capturedImages = capturedImages;
        }

        @Override
        public boolean shouldParseEmbedded(Metadata metadata) {
            String contentType = metadata.get(Metadata.CONTENT_TYPE);
            return contentType != null && contentType.startsWith("image/");
        }

        @Override
        public void parseEmbedded(InputStream stream, ContentHandler handler,
                                  Metadata metadata, boolean outputHtml)
                throws SAXException, IOException {
            String contentType = metadata.get(Metadata.CONTENT_TYPE);
            if (contentType != null && contentType.startsWith("image/")) {
                try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = stream.read(buf)) != -1) {
                        baos.write(buf, 0, n);
                    }
                    capturedImages.add(new ImageData(baos.toByteArray(), contentType));
                }
            }
        }
    }
}
