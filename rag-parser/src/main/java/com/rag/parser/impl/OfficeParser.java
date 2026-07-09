package com.rag.parser.impl;

import com.rag.core.api.DocumentParser;
import com.rag.core.entity.*;
import com.rag.core.enums.FileTypeEnum;
import com.rag.core.exception.RagException;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.sl.usermodel.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;

/**
 * Office文件解析器（Word/PPT/Excel）
 */
public class OfficeParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(OfficeParser.class);

    private static final Set<String> SUPPORTED_TYPES = Set.of("doc", "docx", "ppt", "pptx", "xls", "xlsx");

    @Override
    public DocumentParseResult parse(File file) {
        String ext = getExtension(file.getName());
        try {
            return switch (ext) {
                case "docx" -> parseDocx(file);
                case "doc" -> parseDoc(file);
                case "pptx" -> parsePptx(file);
                case "ppt" -> parsePpt(file);
                case "xlsx" -> parseXlsx(file);
                case "xls" -> parseXls(file);
                default -> throw new RagException("RAG_PARSE_OFFICE", "不支持的Office格式: " + ext);
            };
        } catch (Exception e) {
            log.error("Office解析失败: {}", file.getName(), e);
            throw new RagException("RAG_PARSE_OFFICE", "Office解析失败: " + e.getMessage(), e);
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

    // ============ Word DOCX ============
    private DocumentParseResult parseDocx(File file) throws IOException {
        StringBuilder text = new StringBuilder();
        List<TableUnit> tables = new ArrayList<>();

        try (FileInputStream fis = new FileInputStream(file);
             XWPFDocument doc = new XWPFDocument(fis)) {

            doc.getParagraphs().forEach(p -> text.append(p.getText()).append("\n"));

            doc.getTables().forEach(table -> {
                StringBuilder tableText = new StringBuilder();
                List<String> headers = new ArrayList<>();
                int colCount = table.getRow(0) != null ? table.getRow(0).getTableCells().size() : 0;

                for (int i = 0; i < table.getRows().size(); i++) {
                    var row = table.getRow(i);
                    for (var cell : row.getTableCells()) {
                        String cellText = cell.getText().trim();
                        tableText.append(cellText).append("\t");
                        if (i == 0) headers.add(cellText);
                    }
                    tableText.append("\n");
                }

                tables.add(TableUnit.builder()
                        .tableId(UUID.randomUUID().toString())
                        .content(tableText.toString().trim())
                        .rowCount(table.getRows().size())
                        .colCount(colCount)
                        .headers(headers)
                        .build());
            });
        }

        return buildResult(file, "docx", text.toString().trim(), tables, List.of(), List.of());
    }

    // ============ Word DOC (旧格式) ============
    private DocumentParseResult parseDoc(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             HWPFDocument doc = new HWPFDocument(fis)) {
            String text = doc.getDocumentText();
            return buildResult(file, "doc", text.trim(), List.of(), List.of(), List.of());
        }
    }

    // ============ PPTX ============
    private DocumentParseResult parsePptx(File file) throws IOException {
        StringBuilder text = new StringBuilder();
        try (FileInputStream fis = new FileInputStream(file);
             XMLSlideShow ppt = new XMLSlideShow(fis)) {
            for (Slide slide : ppt.getSlides()) {
                text.append("--- Slide ---\n");
                for (Shape shape : slide.getShapes()) {
                    if (shape instanceof TextShape) {
                        text.append(((TextShape<?, ?>) shape).getText()).append("\n");
                    }
                }
            }
        }
        return buildResult(file, "pptx", text.toString().trim(), List.of(), List.of(), List.of());
    }

    // ============ PPT (旧格式) ============
    private DocumentParseResult parsePpt(File file) throws IOException {
        StringBuilder text = new StringBuilder();
        try (FileInputStream fis = new FileInputStream(file);
             HSLFSlideShow ppt = new HSLFSlideShow(fis)) {
            for (org.apache.poi.hslf.usermodel.HSLFSlide slide : ppt.getSlides()) {
                text.append("--- Slide ---\n");
                for (org.apache.poi.hslf.usermodel.HSLFShape shape : slide.getShapes()) {
                    if (shape instanceof org.apache.poi.hslf.usermodel.HSLFTextShape) {
                        text.append(((org.apache.poi.hslf.usermodel.HSLFTextShape) shape).getText()).append("\n");
                    }
                }
            }
        }
        return buildResult(file, "ppt", text.toString().trim(), List.of(), List.of(), List.of());
    }

    // ============ XLSX ============
    private DocumentParseResult parseXlsx(File file) throws IOException {
        StringBuilder text = new StringBuilder();
        List<TableUnit> tables = new ArrayList<>();

        try (FileInputStream fis = new FileInputStream(file);
             XSSFWorkbook wb = new XSSFWorkbook(fis)) {
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                Sheet sheet = wb.getSheetAt(i);
                if (sheet == null) continue;

                text.append("--- Sheet: ").append(sheet.getSheetName()).append(" ---\n");
                StringBuilder sheetText = new StringBuilder();
                List<String> headers = new ArrayList<>();

                for (Row row : sheet) {
                    StringBuilder rowText = new StringBuilder();
                    for (Cell cell : row) {
                        rowText.append(getCellValue(cell)).append("\t");
                    }
                    if (row.getRowNum() == 0) {
                        for (Cell cell : row) headers.add(getCellValue(cell));
                    }
                    sheetText.append(rowText).append("\n");
                }

                text.append(sheetText);
                tables.add(TableUnit.builder()
                        .tableId(UUID.randomUUID().toString())
                        .content(sheetText.toString().trim())
                        .rowCount(sheet.getLastRowNum() + 1)
                        .colCount(headers.size())
                        .sheetName(sheet.getSheetName())
                        .headers(headers)
                        .build());
            }
        }
        return buildResult(file, "xlsx", text.toString().trim(), tables, List.of(), List.of());
    }

    private DocumentParseResult parseXls(File file) throws IOException {
        StringBuilder text = new StringBuilder();
        List<TableUnit> tables = new ArrayList<>();

        try (FileInputStream fis = new FileInputStream(file);
             HSSFWorkbook wb = new HSSFWorkbook(fis)) {
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                Sheet sheet = wb.getSheetAt(i);
                if (sheet == null) continue;

                text.append("--- Sheet: ").append(sheet.getSheetName()).append(" ---\n");
                StringBuilder sheetText = new StringBuilder();
                List<String> headers = new ArrayList<>();

                for (Row row : sheet) {
                    StringBuilder rowText = new StringBuilder();
                    for (Cell cell : row) {
                        rowText.append(getCellValue(cell)).append("\t");
                    }
                    if (row.getRowNum() == 0) {
                        for (Cell cell : row) headers.add(getCellValue(cell));
                    }
                    sheetText.append(rowText).append("\n");
                }

                text.append(sheetText);
                tables.add(TableUnit.builder()
                        .tableId(UUID.randomUUID().toString())
                        .content(sheetText.toString().trim())
                        .rowCount(sheet.getLastRowNum() + 1)
                        .colCount(headers.size())
                        .sheetName(sheet.getSheetName())
                        .headers(headers)
                        .build());
            }
        }
        return buildResult(file, "xls", text.toString().trim(), tables, List.of(), List.of());
    }

    private String getCellValue(Cell cell) {
        try {
            return switch (cell.getCellType()) {
                case STRING -> cell.getStringCellValue();
                case NUMERIC -> String.valueOf(cell.getNumericCellValue());
                case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                case FORMULA -> cell.getCellFormula();
                default -> "";
            };
        } catch (Exception e) {
            return "";
        }
    }

    private DocumentParseResult buildResult(File file, String fileType, String fullText,
                                            List<TableUnit> tables, List<CodeUnit> codeBlocks,
                                            List<TitleNode> titleTree) {
        DocumentMeta meta = DocumentMeta.builder()
                .fileName(file.getName())
                .fileType(fileType)
                .fileSize(file.length())
                .fileId(UUID.randomUUID().toString())
                .sourcePath(file.getAbsolutePath())
                .build();
        return DocumentParseResult.builder()
                .fullText(fullText)
                .tableList(tables)
                .codeBlockList(codeBlocks)
                .titleTree(titleTree)
                .meta(meta)
                .build();
    }

    private String getExtension(String fileName) {
        int i = fileName.lastIndexOf('.');
        return i > 0 ? fileName.substring(i + 1).toLowerCase() : "";
    }

    private File createTempFile(FileSource fileSource, String ext) {
        try {
            File temp = File.createTempFile("rag_office_", "." + ext);
            java.nio.file.Files.write(temp.toPath(), fileSource.getContent());
            return temp;
        } catch (IOException e) {
            throw new RagException("RAG_FILE_ERR", "创建临时文件失败", e);
        }
    }
}
