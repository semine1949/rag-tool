package com.rag.chunker.impl;

import com.rag.core.api.TextChunker;
import com.rag.core.config.ChunkConfig;
import com.rag.core.entity.Chunk;
import com.rag.core.entity.DocumentParseResult;
import com.rag.core.enums.ChunkStrategyEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 父子分片策略
 * 父块为大段摘要（大窗口），子块为细分片段（小窗口），关联parentId
 * 注意：此策略通常作为最后一步，对已有chunk进行父子包装
 */
public class ParentChildChunker implements TextChunker {

    private static final Logger log = LoggerFactory.getLogger(ParentChildChunker.class);

    @Override
    public List<Chunk> chunk(DocumentParseResult parseResult, ChunkConfig config) {
        String text = parseResult.getFullText();
        if (text == null || text.isEmpty()) return List.of();

        int parentLen = config.getParentChunkLen() != null ? config.getParentChunkLen() : 1000;
        int childLen = config.getChildChunkLen() != null ? config.getChildChunkLen() : 200;

        List<Chunk> chunks = new ArrayList<>();

        // 生成父块（大窗口）
        List<Chunk> parents = splitFixed(text, parentLen, parentLen / 4);
        // 生成子块（小窗口）
        List<Chunk> children = splitFixed(text, childLen, childLen / 4);

        // 关联父子关系
        for (Chunk parent : parents) {
            chunks.add(parent);
            String parentId = parent.getChunkId();
            // 找到落在父块范围内的子块
            for (Chunk child : children) {
                if (child.getText() != null && parent.getText().contains(child.getText())) {
                    child.setParentId(parentId);
                }
            }
        }

        // 把子块也加入（如果包含了子块策略）
        chunks.addAll(children);

        log.debug("父子分片完成，父块{}个，子块{}个", parents.size(), children.size());
        return chunks;
    }

    @Override
    public ChunkStrategyEnum getStrategy() {
        return ChunkStrategyEnum.PARENT_CHILD;
    }

    private List<Chunk> splitFixed(String text, int chunkSize, int overlap) {
        List<Chunk> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            // 在空白处截断
            if (end < text.length()) {
                int bp = findBreakPoint(text, end);
                if (bp > start && bp <= end + overlap) end = bp;
            }
            String chunkText = text.substring(start, end).trim();
            if (!chunkText.isEmpty()) {
                chunks.add(Chunk.builder()
                        .chunkId(UUID.randomUUID().toString())
                        .text(chunkText)
                        .chunkType(ChunkStrategyEnum.PARENT_CHILD)
                        .textHash(hash(chunkText))
                        .build());
            }
            start = end - overlap;
            if (start >= text.length() || end >= text.length()) break;
        }
        return chunks;
    }

    private int findBreakPoint(String text, int position) {
        int searchStart = Math.max(0, position - 100);
        String window = text.substring(searchStart, Math.min(text.length(), position + 50));
        int relativePos = position - searchStart;
        for (char ch : new char[]{'。', '！', '？', '.', '!', '?', '\n'}) {
            int idx = window.lastIndexOf(ch, relativePos);
            if (idx > 0) return searchStart + idx + 1;
        }
        return position;
    }

    private String hash(String text) {
        int h = 0;
        for (char c : text.toCharArray()) h = 31 * h + c;
        return Integer.toHexString(h);
    }
}
