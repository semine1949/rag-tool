package com.rag.chunker.impl;

import com.rag.chunker.ConfigurableTextSplitter;
import com.rag.chunker.util.ChunkDocuments;
import com.rag.core.config.ChunkConfig;
import com.rag.core.enums.ChunkStrategyEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

import java.util.*;

/**
 * 表格独立分片策略：从文本中抽取 Markdown 表格，每个表格单独生成切片并附带行列元数据。
 * 实现 Spring AI {@link ConfigurableTextSplitter}，产出 {@link Document} 列表。
 */
public class TableChunker extends ConfigurableTextSplitter {

    private static final Logger log = LoggerFactory.getLogger(TableChunker.class);
    private ChunkConfig config;

    @Override
    public void setConfig(ChunkConfig config) {
        this.config = config;
    }

    @Override
    public List<Document> apply(List<Document> documents) {
        if (config == null || !Boolean.TRUE.equals(config.getSplitTableSingleChunk())) {
            return List.of();
        }
        String text = ChunkDocuments.fullText(documents);
        if (text.isEmpty()) {
            return List.of();
        }
        Document input = documents.get(0);
        List<Document> chunks = new ArrayList<>();
        for (String table : extractMarkdownTables(text)) {
            String[] lines = table.split("\n");
            int rowCount = Math.max(0, lines.length - 2);
            int colCount = lines.length > 0 ? Math.max(0, lines[0].split("\\|", -1).length - 2) : 0;

            Map<String, Object> tableMeta = new HashMap<>();
            tableMeta.put("rowCount", rowCount);
            tableMeta.put("colCount", colCount);

            Map<String, Object> extra = new HashMap<>();
            extra.put("tableFlag", true);
            extra.put("tableMeta", tableMeta);

            chunks.add(ChunkDocuments.of(input, table, ChunkStrategyEnum.TABLE.name(), extra));
        }
        log.debug("表格分片完成，生成{}个chunk", chunks.size());
        return chunks;
    }

    private List<String> extractMarkdownTables(String text) {
        List<String> tables = new ArrayList<>();
        String[] lines = text.split("\n");
        StringBuilder current = null;
        for (String line : lines) {
            String trimmed = line.trim();
            boolean isTableLine = trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.length() > 1;
            if (isTableLine) {
                if (current == null) {
                    current = new StringBuilder();
                }
                current.append(line).append("\n");
            } else {
                if (current != null) {
                    String t = current.toString().trim();
                    if (isLikelyTable(t)) {
                        tables.add(t);
                    }
                    current = null;
                }
            }
        }
        if (current != null) {
            String t = current.toString().trim();
            if (isLikelyTable(t)) {
                tables.add(t);
            }
        }
        return tables;
    }

    private boolean isLikelyTable(String block) {
        String[] lines = block.split("\n");
        if (lines.length < 2) {
            return false;
        }
        for (String line : lines) {
            if (line.trim().matches("^\\|?[:\\s\\-]+\\|?$") && line.contains("-")) {
                return true;
            }
        }
        return false;
    }
}
