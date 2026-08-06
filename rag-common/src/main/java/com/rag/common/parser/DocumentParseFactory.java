package com.rag.common.parser;

import com.rag.common.enums.FileTypeEnum;
import com.rag.common.exception.RagException;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * 文档解析工厂 - 根据文件类型自动匹配 Spring AI {@link DocumentReader} 并解析为 {@link Document} 列表。
 * <p>
 * 文本 / Markdown 交由框架 {@code TextReader}；HTML / PDF 交由框架 {@code TikaDocumentReader}；
 * Excel 与 Word/PPT/图片等交由自研（OCR / POI）解析器。
 */
public class DocumentParseFactory {

    private final Map<FileTypeEnum, Function<File, DocumentReader>> readerSuppliers;

    public DocumentParseFactory(Map<FileTypeEnum, Function<File, DocumentReader>> readerSuppliers) {
        this.readerSuppliers = readerSuppliers;
    }

    /**
     * 根据文件自动匹配解析器并解析为 Document 列表。
     * 解析后统一补充 fileId 元数据（单文件内所有切片共享，便于按文件删除）。
     */
    public List<Document> parse(File file) {
        String ext = getFileExtension(file.getName());
        FileTypeEnum type = FileTypeEnum.fromExtension(ext);
        Function<File, DocumentReader> supplier = readerSuppliers.get(type);
        if (supplier == null) {
            supplier = readerSuppliers.get(FileTypeEnum.TXT);
        }
        if (supplier == null) {
            throw new RagException("RAG_PARSE_001", "不支持的文件类型: " + ext);
        }
        List<Document> docs = supplier.apply(file).get();
        if (docs.isEmpty()) {
            throw new RagException("RAG_PARSE_002", "解析结果为空: " + file.getName());
        }
        String fileId = UUID.randomUUID().toString();
        for (Document doc : docs) {
            doc.getMetadata().putIfAbsent("fileId", fileId);
        }
        return docs;
    }

    private String getFileExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf(".") + 1);
    }
}
