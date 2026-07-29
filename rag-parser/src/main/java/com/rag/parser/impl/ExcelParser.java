package com.rag.parser.impl;

import com.rag.core.enums.FileTypeEnum;
import com.rag.core.exception.RagException;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/**
 * Excel 文件解析器（.xls / .xlsx）
 * <p>
 * 支持两种向量化策略，按每 sheet 的数据行数自动判定：
 * <ul>
 *     <li>数据行数 ≤ {@link #MARKDOWN_ROW_THRESHOLD}：方案二 —— 整体转为 Markdown 表格（适用于表头复杂、行数少的场景）</li>
 *     <li>数据行数 &gt; {@link #MARKDOWN_ROW_THRESHOLD}：方案一 —— 按行拆分为"键值对"格式（默认策略）</li>
 * </ul>
 * <p>
 * 处理合并单元格、空行/空值/Null/"null" 过滤，避免污染向量库。
 * <p>
 * 实现 Spring AI {@link DocumentReader} 接口，统一返回承载全文的 {@link Document}。
 *
 * @author auto
 * @since 2026-07-08
 */
@Slf4j
public class ExcelParser implements DocumentReader {

    /**
     * 行数阈值：数据行数 ≤ 此值使用 Markdown 表格方案，> 此值使用键值对方案
     */
    private static final int MARKDOWN_ROW_THRESHOLD = 20;

    private static final Set<String> SUPPORTED_TYPES = Set.of("xls", "xlsx");

    private final File file;

    public ExcelParser(File file) {
        this.file = file;
    }

    @Override
    public List<Document> get() {
        String filename = file.getName();
        String fileType = getFileType(filename);

        try {
            byte[] fileBytes = Files.readAllBytes(file.toPath());
            String fullText = doParse(fileBytes, filename);

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("fileName", filename);
            metadata.put("fileType", fileType);
            metadata.put("fileSize", file.length());
            metadata.put("sourcePath", file.getAbsolutePath());
            return List.of(new Document(UUID.randomUUID().toString(), fullText, metadata));
        } catch (IOException e) {
            log.error("Excel文件读取失败: {}", filename, e);
            throw new RagException("RAG_PARSE_EXCEL", "Excel文件读取失败: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // 内部解析入口
    // -------------------------------------------------------------------------

    /**
     * 解析 Excel 字节数组，产出统一文本内容。
     */
    private String doParse(byte[] fileBytes, String filename) {
        List<String> sheetContents = new ArrayList<>();

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(fileBytes))) {
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                String sheetName = sheet.getSheetName();
                int firstRow = sheet.getFirstRowNum();
                int lastRow = sheet.getLastRowNum();
                if (firstRow < 0) {
                    log.info("Sheet [{}] 无数据行，跳过", sheetName);
                    continue;
                }

                // 预计算合并单元格映射表
                Map<String, String> mergedMap = buildMergedRegionMap(sheet, evaluator);

                // 计算整张 sheet 的最大列数
                int sheetMaxCol = getMaxColumn(sheet, firstRow, lastRow);

                // 查找表头行
                int headerRowIdx = findFirstNonEmptyRow(sheet, firstRow, lastRow, sheetMaxCol, mergedMap, evaluator);
                if (headerRowIdx < 0) {
                    log.info("Sheet [{}] 无有效数据行，跳过", sheetName);
                    continue;
                }

                // 统计有效数据行数
                int dataRowCount = 0;
                for (int r = headerRowIdx + 1; r <= lastRow; r++) {
                    Row row = sheet.getRow(r);
                    if (row != null && !isEmptyRow(sheet, row, sheetMaxCol, mergedMap, evaluator)) {
                        dataRowCount++;
                    }
                }

                log.info("Sheet [{}] 策略: {}, 有效数据行: {}", sheetName,
                        dataRowCount <= MARKDOWN_ROW_THRESHOLD ? "markdown" : "key_value", dataRowCount);

                String sheetContent;
                if (dataRowCount <= MARKDOWN_ROW_THRESHOLD) {
                    sheetContent = parseSheetAsMarkdown(
                            sheet, sheetName, headerRowIdx, sheetMaxCol, mergedMap, evaluator);
                } else {
                    sheetContent = parseSheetAsKeyValue(
                            sheet, sheetName, headerRowIdx, sheetMaxCol, mergedMap, evaluator);
                }
                if (!sheetContent.isEmpty()) {
                    sheetContents.add(sheetContent);
                }
            }
        } catch (Exception e) {
            log.error("Excel解析失败: {}", filename, e);
            throw new RagException("RAG_PARSE_EXCEL", "Excel解析失败: " + filename, e);
        }

        return String.join("\n\n---\n\n", sheetContents);
    }

    // -------------------------------------------------------------------------
    // 方案一：按行拆分为键值对
    // -------------------------------------------------------------------------

    /**
     * 方案一：按行拆分为"键值对"格式文本。
     * <p>
     * 第一非空行作为表头（列名），后续每一行构建一行键值对。
     * 单行内容格式："列名1: 值1, 列名2: 值2"。
     * 空值列跳过不输出；全空行跳过。
     */
    private String parseSheetAsKeyValue(Sheet sheet, String sheetName, int headerRowIdx, int maxCol,
                                        Map<String, String> mergedMap, FormulaEvaluator evaluator) {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(sheetName).append("\n\n");

        // 读取表头
        Row headerRow = sheet.getRow(headerRowIdx);
        String[] headers = new String[maxCol + 1];
        for (int c = 0; c <= maxCol; c++) {
            String h = getCellText(sheet, headerRow, c, mergedMap, evaluator);
            headers[c] = h.isEmpty() ? ("列" + (c + 1)) : h;
        }

        // 逐行产出键值对
        int dataIdx = 0;
        for (int r = headerRowIdx + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            if (isEmptyRow(sheet, row, maxCol, mergedMap, evaluator)) {
                continue;
            }

            StringBuilder line = new StringBuilder();
            boolean hasField = false;
            for (int c = 0; c <= maxCol; c++) {
                String value = getCellText(sheet, row, c, mergedMap, evaluator);
                if (value.isEmpty()) {
                    continue;
                }
                if (hasField) {
                    line.append(", ");
                }
                line.append(headers[c]).append(": ").append(value);
                hasField = true;
            }

            // 过滤后若全空，跳过本行
            if (!hasField) {
                continue;
            }

            dataIdx++;
            if (dataIdx > 1) {
                sb.append("\n");
            }
            sb.append(line);
        }

        log.info("Sheet [{}] 键值对方案产出 {} 条数据行", sheetName, dataIdx);
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // 方案二：整体转为 Markdown 表格
    // -------------------------------------------------------------------------

    /**
     * 方案二：将整张 sheet（含表头）转为 Markdown 表格文本。
     * <p>
     * 格式：
     * <pre>
     * ## 表名
     *
     * | 列1 | 列2 | ... |
     * | --- | --- | ... |
     * | v1  | v2  | ... |
     * </pre>
     * 空行跳过，空单元格保留空串。
     */
    private String parseSheetAsMarkdown(Sheet sheet, String sheetName, int headerRowIdx, int maxCol,
                                        Map<String, String> mergedMap, FormulaEvaluator evaluator) {
        StringBuilder md = new StringBuilder();
        md.append("## ").append(sheetName).append("\n\n");

        // 表头行
        Row headerRow = sheet.getRow(headerRowIdx);
        StringBuilder headerLine = new StringBuilder("|");
        StringBuilder sepLine = new StringBuilder("|");
        for (int c = 0; c <= maxCol; c++) {
            String h = getCellText(sheet, headerRow, c, mergedMap, evaluator);
            headerLine.append(" ").append(h.isEmpty() ? ("列" + (c + 1)) : h).append(" |");
            sepLine.append(" --- |");
        }
        md.append(headerLine).append("\n").append(sepLine).append("\n");

        // 数据行
        for (int r = headerRowIdx + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            if (isEmptyRow(sheet, row, maxCol, mergedMap, evaluator)) {
                continue;
            }

            StringBuilder dataLine = new StringBuilder("|");
            for (int c = 0; c <= maxCol; c++) {
                String value = getCellText(sheet, row, c, mergedMap, evaluator);
                dataLine.append(" ").append(value).append(" |");
            }
            md.append(dataLine).append("\n");
        }

        log.info("Sheet [{}] Markdown 表格方案产出 1 条切片", sheetName);
        return md.toString();
    }

    // -------------------------------------------------------------------------
    // 值提取：合并单元格解析 + 空值/Null/"null" 过滤
    // -------------------------------------------------------------------------

    /**
     * 获取指定单元格的文本值（已解析合并单元格、公式、空值过滤）。
     * <p>
     * 优先从合并单元格映射获取（保证合并区域内任意位置读到统一值）；
     * 如不在合并区域，直接读原始单元格值。
     */
    private String getCellText(Sheet sheet, Row row, int colIdx,
                               Map<String, String> mergedMap, FormulaEvaluator evaluator) {
        // 合并单元格映射优先
        String mergedValue = mergedMap.get(row.getRowNum() + "-" + colIdx);
        if (mergedValue != null) {
            return mergedValue.trim();
        }

        Cell cell = row.getCell(colIdx, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
        return getRawCellValue(cell, evaluator);
    }

    /**
     * 提取单元格的原始字符串值。
     * <p>
     * 处理规则：
     * <ul>
     *     <li>null / BLANK / ERROR → 空串</li>
     *     <li>STRING → trim() 后返回；若值为字面量 "null"（不区分大小写），视作空值</li>
     *     <li>NUMERIC → 整型不显示小数点，日期格式返回 yyyy-MM-dd</li>
     *     <li>BOOLEAN → "true"/"false"</li>
     *     <li>FORMULA → 评估公式结果后递归获取</li>
     * </ul>
     */
    private String getRawCellValue(Cell cell, FormulaEvaluator evaluator) {
        if (cell == null) {
            return "";
        }

        switch (cell.getCellType()) {
            case STRING:
                String sv = cell.getStringCellValue().trim();
                if ("null".equalsIgnoreCase(sv)) {
                    return "";
                }
                return sv;

            case NUMERIC:
                if (org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
                    try {
                        return cell.getLocalDateTimeCellValue().toLocalDate().toString();
                    } catch (Exception e) {
                        return String.valueOf(cell.getNumericCellValue());
                    }
                }
                double d = cell.getNumericCellValue();
                if (d == Math.floor(d) && !Double.isInfinite(d)
                        && d <= Long.MAX_VALUE && d >= Long.MIN_VALUE) {
                    return String.valueOf((long) d);
                }
                return String.valueOf(d);

            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());

            case FORMULA:
                try {
                    CellValue cv = evaluator.evaluate(cell);
                    if (cv == null) {
                        return "";
                    }
                    switch (cv.getCellType()) {
                        case STRING:
                            String fsv = cv.getStringValue().trim();
                            if ("null".equalsIgnoreCase(fsv)) {
                                return "";
                            }
                            return fsv;
                        case NUMERIC:
                            double nd = cv.getNumberValue();
                            if (nd == Math.floor(nd) && !Double.isInfinite(nd)) {
                                return String.valueOf((long) nd);
                            }
                            return String.valueOf(nd);
                        case BOOLEAN:
                            return String.valueOf(cv.getBooleanValue());
                        default:
                            return "";
                    }
                } catch (Exception e) {
                    log.debug("公式评估失败: {}", cell.getCellFormula());
                    return "";
                }

            case BLANK:
            case ERROR:
            default:
                return "";
        }
    }

    // -------------------------------------------------------------------------
    // 空行 / 空值判断
    // -------------------------------------------------------------------------

    /**
     * 判断整行是否为空（所有列的值 trim() 后为空）。
     */
    private boolean isEmptyRow(Sheet sheet, Row row, int maxCol,
                               Map<String, String> mergedMap, FormulaEvaluator evaluator) {
        if (row == null) {
            return true;
        }
        for (int c = 0; c <= maxCol; c++) {
            String val = getCellText(sheet, row, c, mergedMap, evaluator);
            if (!val.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 在指定行范围内查找第一个非空行索引（用作表头）。
     *
     * @return 非空行索引；若无有效行返回 -1
     */
    private int findFirstNonEmptyRow(Sheet sheet, int firstRow, int lastRow, int maxCol,
                                     Map<String, String> mergedMap, FormulaEvaluator evaluator) {
        for (int r = firstRow; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            if (!isEmptyRow(sheet, row, maxCol, mergedMap, evaluator)) {
                return r;
            }
        }
        return -1;
    }

    // -------------------------------------------------------------------------
    // 合并单元格预处理
    // -------------------------------------------------------------------------

    /**
     * 预计算整个 sheet 的合并单元格映射表。
     * <p>
     * Key 格式："{row}-{col}"，Value = 合并区域左上角单元格的已解析文本值。
     * <p>
     * 这样在遍历任意单元格时只需查一次 Map，避免每格都遍历所有合并区域。
     */
    private Map<String, String> buildMergedRegionMap(Sheet sheet, FormulaEvaluator evaluator) {
        Map<String, String> map = new HashMap<>();
        for (CellRangeAddress region : sheet.getMergedRegions()) {
            Row topRow = sheet.getRow(region.getFirstRow());
            if (topRow == null) {
                continue;
            }
            Cell topCell = topRow.getCell(region.getFirstColumn(),
                    Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
            String value = getRawCellValue(topCell, evaluator);
            for (int r = region.getFirstRow(); r <= region.getLastRow(); r++) {
                for (int c = region.getFirstColumn(); c <= region.getLastColumn(); c++) {
                    map.put(r + "-" + c, value);
                }
            }
        }
        return map;
    }

    // -------------------------------------------------------------------------
    // 工具方法
    // -------------------------------------------------------------------------

    /**
     * 计算指定行范围内的最大列索引（0-based）。
     */
    private int getMaxColumn(Sheet sheet, int firstRow, int lastRow) {
        int max = 0;
        for (int r = firstRow; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row != null && row.getLastCellNum() > max) {
                max = row.getLastCellNum();
            }
        }
        return max - 1;
    }

    /**
     * 提取文件扩展名。
     */
    private String getFileType(String filename) {
        int i = filename.lastIndexOf('.');
        return i > 0 ? filename.substring(i + 1).toLowerCase() : "xlsx";
    }
}
