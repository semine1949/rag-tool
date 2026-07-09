package com.rag.parser.impl;

import com.rag.core.api.DocumentParser;
import com.rag.core.entity.*;
import com.rag.core.enums.FileTypeEnum;
import com.rag.core.exception.RagException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * PDF文件解析器
 * 支持文字提取，按页码拆分文本
 */
public class PdfParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(PdfParser.class);

    @Override
    public DocumentParseResult parse(File file) {
        try (PDDocument document = Loader.loadPDF(file)) {
            int totalPages = document.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            StringBuilder fullText = new StringBuilder();

            // 逐页提取，保留页码信息
            List<TableUnit> tables = new ArrayList<>();
            List<CodeUnit> codeBlocks = new ArrayList<>();
            List<TitleNode> titleTree = new ArrayList<>();

            stripper.setStartPage(1);
            stripper.setEndPage(totalPages);
            String text = stripper.getText(document);
            fullText.append(text);

            DocumentMeta meta = DocumentMeta.builder()
                    .fileName(file.getName())
                    .fileType(FileTypeEnum.PDF.getExtension())
                    .fileSize(file.length())
                    .fileId(UUID.randomUUID().toString())
                    .sourcePath(file.getAbsolutePath())
                    .totalPages(totalPages)
                    .build();

            return DocumentParseResult.builder()
                    .fullText(fullText.toString().trim())
                    .tableList(tables)
                    .codeBlockList(codeBlocks)
                    .titleTree(titleTree)
                    .meta(meta)
                    .build();
        } catch (IOException e) {
            log.error("PDF解析失败: {}", file.getName(), e);
            throw new RagException("RAG_PARSE_PDF", "PDF解析失败: " + e.getMessage(), e);
        }
    }

    @Override
    public DocumentParseResult parse(FileSource fileSource) {
        File tempFile = createTempFile(fileSource);
        try {
            return parse(tempFile);
        } finally {
            tempFile.delete();
        }
    }

    @Override
    public boolean support(String fileType) {
        return "pdf".equalsIgnoreCase(fileType);
    }

    private File createTempFile(FileSource fileSource) {
        try {
            File temp = File.createTempFile("rag_pdf_", ".pdf");
            java.nio.file.Files.write(temp.toPath(), fileSource.getContent());
            return temp;
        } catch (IOException e) {
            throw new RagException("RAG_FILE_ERR", "创建临时文件失败", e);
        }
    }
}
