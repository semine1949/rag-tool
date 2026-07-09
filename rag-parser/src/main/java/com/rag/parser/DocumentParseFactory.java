package com.rag.parser;

import com.rag.core.api.DocumentParser;
import com.rag.core.entity.*;
import com.rag.core.enums.FileTypeEnum;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文档解析工厂 - 根据文件类型自动匹配解析器
 */
public class DocumentParseFactory {

    private final Map<FileTypeEnum, DocumentParser> parserMap;

    public DocumentParseFactory(Map<FileTypeEnum, DocumentParser> parserMap) {
        this.parserMap = parserMap;
    }

    /**
     * 根据文件自动匹配解析器并解析
     */
    public DocumentParseResult parse(File file) {
        String ext = getFileExtension(file.getName());
        FileTypeEnum type = FileTypeEnum.fromExtension(ext);
        DocumentParser parser = parserMap.get(type);
        if (parser == null) {
            // 尝试用TEXT类型作为fallback
            parser = parserMap.get(FileTypeEnum.TXT);
        }
        if (parser == null) {
            throw new com.rag.core.exception.RagException("RAG_PARSE_001", "不支持的文件类型: " + ext);
        }
        return parser.parse(file);
    }

    /**
     * 解析FileSource
     */
    public DocumentParseResult parse(FileSource fileSource) {
        String ext = getFileExtension(fileSource.getFileName());
        FileTypeEnum type = FileTypeEnum.fromExtension(ext);
        DocumentParser parser = parserMap.get(type);
        if (parser == null) {
            parser = parserMap.get(FileTypeEnum.TXT);
        }
        if (parser == null) {
            throw new com.rag.core.exception.RagException("RAG_PARSE_001", "不支持的文件类型: " + ext);
        }
        return parser.parse(fileSource);
    }

    private String getFileExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) return "";
        return fileName.substring(fileName.lastIndexOf(".") + 1);
    }

    /**
     * 获取所有已注册的解析器
     */
    public Map<FileTypeEnum, DocumentParser> getParsers() {
        return parserMap;
    }
}
