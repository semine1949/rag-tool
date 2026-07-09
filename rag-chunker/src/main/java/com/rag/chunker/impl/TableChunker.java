package com.rag.chunker.impl;

import com.rag.core.api.TextChunker;
import com.rag.core.config.ChunkConfig;
import com.rag.core.entity.*;
import com.rag.core.enums.ChunkStrategyEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 表格独立分片策略
 * 每个表格单独生成Chunk，附带表格行列元数据
 */
public class TableChunker implements TextChunker {

    private static final Logger log = LoggerFactory.getLogger(TableChunker.class);

    @Override
    public List<Chunk> chunk(DocumentParseResult parseResult, ChunkConfig config) {
        if (!Boolean.TRUE.equals(config.getSplitTableSingleChunk())) {
            return List.of();
        }

        List<TableUnit> tables = parseResult.getTableList();
        if (tables == null || tables.isEmpty()) {
            return List.of();
        }

        List<Chunk> chunks = new ArrayList<>();
        for (TableUnit table : tables) {
            Map<String, Object> tableMeta = new HashMap<>();
            tableMeta.put("tableId", table.getTableId());
            tableMeta.put("rowCount", table.getRowCount());
            tableMeta.put("colCount", table.getColCount());
            tableMeta.put("sheetName", table.getSheetName());
            tableMeta.put("headers", table.getHeaders());

            Map<String, Object> extraMeta = new HashMap<>();
            extraMeta.put("tableFlag", true);

            chunks.add(Chunk.builder()
                    .chunkId(UUID.randomUUID().toString())
                    .text(table.getContent())
                    .chunkType(ChunkStrategyEnum.TABLE)
                    .textHash(hash(table.getContent()))
                    .fileName(parseResult.getMeta() != null ? parseResult.getMeta().getFileName() : null)
                    .fileType(parseResult.getMeta() != null ? parseResult.getMeta().getFileType() : null)
                    .fileId(parseResult.getMeta() != null ? parseResult.getMeta().getFileId() : null)
                    .pageNo(table.getPageNo())
                    .tableMeta(tableMeta)
                    .extraMeta(extraMeta)
                    .build());
        }

        log.debug("表格分片完成，生成{}个chunk", chunks.size());
        return chunks;
    }

    @Override
    public ChunkStrategyEnum getStrategy() {
        return ChunkStrategyEnum.TABLE;
    }

    private String hash(String text) {
        int h = 0;
        for (char c : text.toCharArray()) h = 31 * h + c;
        return Integer.toHexString(h);
    }
}
