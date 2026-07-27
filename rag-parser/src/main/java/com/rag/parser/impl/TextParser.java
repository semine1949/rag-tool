package com.rag.parser.impl;

import com.rag.core.api.DocumentParser;
import com.rag.core.entity.*;
import com.rag.core.exception.RagException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 纯文本 / Markdown 解析器。
 * <p>
 * 仅做本地纯文本读取（TXT / MD），不依赖任何需要安装外部工具/原生库的解析库。
 * HTML / PDF / Office 等已由 {@link DeepSeekOcrParser} 走远程 DeepSeek-OCR 解析。
 * Markdown 在此作为纯文本读取（保留原文，标题/代码块层级交由后续分片策略处理）。
 */
public class TextParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(TextParser.class);

    private static final Set<String> SUPPORTED_TYPES = Set.of("txt", "md", "markdown");

    @Override
    public DocumentParseResult parse(File file) {
        String ext = getExtension(file.getName()).toLowerCase();
        try {
            String content = Files.readString(file.toPath());
            return buildResult(file, ext, content);
        } catch (IOException e) {
            log.error("文本解析失败: {}", file.getName(), e);
            throw new RagException("RAG_PARSE_TEXT", "文本解析失败: " + e.getMessage(), e);
        }
    }

    @Override
    public DocumentParseResult parse(FileSource fileSource) {
        File temp = createTempFile(fileSource, getExtension(fileSource.getFileName()));
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

    private DocumentParseResult buildResult(File file, String fileType, String fullText) {
        DocumentMeta meta = DocumentMeta.builder()
                .fileName(file.getName())
                .fileType(fileType)
                .fileSize(file.length())
                .fileId(UUID.randomUUID().toString())
                .sourcePath(file.getAbsolutePath())
                .build();
        return DocumentParseResult.builder()
                .fullText(fullText)
                .tableList(List.of())
                .codeBlockList(List.of())
                .titleTree(List.of())
                .meta(meta)
                .build();
    }

    private String getExtension(String fileName) {
        int i = fileName.lastIndexOf('.');
        return i > 0 ? fileName.substring(i + 1).toLowerCase() : "txt";
    }

    private File createTempFile(FileSource fileSource, String ext) {
        try {
            File temp = File.createTempFile("rag_text_", "." + ext);
            Files.write(temp.toPath(), fileSource.getContent());
            return temp;
        } catch (IOException e) {
            throw new RagException("RAG_FILE_ERR", "创建临时文件失败", e);
        }
    }
}
