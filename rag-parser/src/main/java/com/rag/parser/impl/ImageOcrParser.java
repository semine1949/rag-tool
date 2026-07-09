package com.rag.parser.impl;

import com.rag.core.api.DocumentParser;
import com.rag.core.entity.*;
import com.rag.core.enums.FileTypeEnum;
import com.rag.core.exception.RagException;
import net.sourceforge.tess4j.Tesseract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 图片OCR解析器
 * 支持JPG/PNG/BMP图片文字识别
 */
public class ImageOcrParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(ImageOcrParser.class);
    private static final Set<String> SUPPORTED_TYPES = Set.of("jpg", "jpeg", "png", "bmp");

    // TODO: 配置Tesseract OCR数据路径，例如 D:/tessdata 或 /usr/share/tesseract-ocr/5/tessdata
    private final String ocrDataPath;
    // TODO: 配置OCR识别语言，例如 "chi_sim+eng"（中文简体+英文）
    private final String ocrLanguage;

    public ImageOcrParser(String ocrDataPath, String ocrLanguage) {
        this.ocrDataPath = ocrDataPath;
        this.ocrLanguage = ocrLanguage;
    }

    @Override
    public DocumentParseResult parse(File file) {
        try {
            Tesseract tesseract = new Tesseract();
            tesseract.setDatapath(ocrDataPath);
            tesseract.setLanguage(ocrLanguage);
            String text = tesseract.doOCR(file).trim();

            if (text.isEmpty()) {
                log.warn("图片OCR未识别到文字: {}", file.getName());
            }

            return buildResult(file, text);
        } catch (Exception e) {
            log.error("OCR解析失败: {}", file.getName(), e);
            throw new RagException("RAG_PARSE_OCR", "图片OCR解析失败: " + e.getMessage(), e);
        }
    }

    @Override
    public DocumentParseResult parse(FileSource fileSource) {
        File temp = createTempFile(fileSource);
        try {
            return parse(temp);
        } finally {
            temp.delete();
        }
    }

    @Override
    public boolean support(String fileType) {
        return SUPPORTED_TYPES.contains(fileType.toLowerCase());
    }

    private DocumentParseResult buildResult(File file, String text) {
        DocumentMeta meta = DocumentMeta.builder()
                .fileName(file.getName())
                .fileType(getExtension(file.getName()))
                .fileSize(file.length())
                .fileId(UUID.randomUUID().toString())
                .sourcePath(file.getAbsolutePath())
                .build();
        return DocumentParseResult.builder()
                .fullText(text)
                .tableList(List.of())
                .codeBlockList(List.of())
                .titleTree(List.of())
                .meta(meta)
                .build();
    }

    private String getExtension(String fileName) {
        int i = fileName.lastIndexOf('.');
        return i > 0 ? fileName.substring(i + 1).toLowerCase() : "";
    }

    private File createTempFile(FileSource fileSource) {
        try {
            String ext = getExtension(fileSource.getFileName());
            File temp = File.createTempFile("rag_ocr_", "." + ext);
            Files.write(temp.toPath(), fileSource.getContent());
            return temp;
        } catch (IOException e) {
            throw new RagException("RAG_FILE_ERR", "创建临时文件失败", e);
        }
    }
}
