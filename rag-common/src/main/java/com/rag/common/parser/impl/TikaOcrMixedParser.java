package com.rag.common.parser.impl;

import com.rag.common.exception.RagException;
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
 * 文本+图片混合文档解析器（Tika 文本提取 + 内嵌图片多模态 OCR）。
 * <p>
 * 适用文件类型：PDF / DOC / DOCX / PPT / PPTX 等常包含内嵌图片的 Office 文档。
 * <p>
 * 解析流程：
 * <ol>
 *     <li>通过 Apache Tika {@link AutoDetectParser} 提取文本层</li>
 *     <li>通过 {@link EmbeddedDocumentExtractor} 截获内嵌图片（image/*）字节</li>
 *     <li>对每张内嵌图片调用 {@link DeepSeekOcrClient} 做 OCR</li>
 *     <li>产出：1 个文本 Document + 每个内嵌图片 1 个 OCR Document</li>
 * </ol>
 * <p>
 * 若解析后无任何有效内容（文本为空且无内嵌图片），自动回退为全量文档 OCR。
 * <p>
 * 实现 Spring AI {@link DocumentReader} 接口。
 */
public class TikaOcrMixedParser implements DocumentReader {

    private static final Logger log = LoggerFactory.getLogger(TikaOcrMixedParser.class);

    private static final Map<String, String> EXT_MIME_MAP = Map.of(
            "pdf", "application/pdf",
            "doc", "application/msword",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "ppt", "application/vnd.ms-powerpoint",
            "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    );

    private final File file;
    private final DeepSeekOcrClient ocrClient;

    public TikaOcrMixedParser(DeepSeekOcrClient ocrClient, File file) {
        this.ocrClient = ocrClient;
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

            // 2. 产出文本 Document（若 Tika 提取到文本）
            if (!extractedText.isEmpty()) {
                Map<String, Object> textMeta = buildBaseMetadata("text");
                textMeta.put("contentType", "text");
                documents.add(new Document(UUID.randomUUID().toString(), extractedText, textMeta));
                log.info("Tika 文本提取成功: {}, size={}", file.getName(), extractedText.length());
            } else {
                log.warn("Tika 未提取到文本: {}", file.getName());
            }

            // 3. 对内嵌图片逐张做多模态 OCR
            for (int i = 0; i < images.size(); i++) {
                ImageData img = images.get(i);
                try {
                    String ocrText = ocrClient.ocr(img.bytes, img.mimeType);
                    if (!ocrText.isEmpty()) {
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

            // 4. 若文本+OCR均无产出，回退全量 OCR（纯扫描件等场景）
            if (documents.isEmpty()) {
                log.warn("Tika+内嵌图片OCR均无产出，回退全量OCR: {}", file.getName());
                String ext = getExtension(file.getName());
                String mime = EXT_MIME_MAP.getOrDefault(ext, "application/octet-stream");
                byte[] fileBytes = Files.readAllBytes(file.toPath());
                String ocrText = ocrClient.ocr(fileBytes, mime);
                if (!ocrText.isEmpty()) {
                    Map<String, Object> fallbackMeta = buildBaseMetadata("fallback_ocr");
                    fallbackMeta.put("contentType", "fallback_ocr");
                    documents.add(new Document(UUID.randomUUID().toString(), ocrText, fallbackMeta));
                    log.info("回退全量OCR成功: {}, size={}", file.getName(), ocrText.length());
                } else {
                    log.error("回退全量OCR也无产出: {}", file.getName());
                }
            }

        } catch (IOException | SAXException | TikaException e) {
            throw new RagException("RAG_PARSE_MIXED", "混合解析失败: " + e.getMessage(), e);
        }

        return documents;
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
