package com.rag.chunker.impl;

import com.rag.core.api.TextChunker;
import com.rag.core.config.ChunkConfig;
import com.rag.core.entity.Chunk;
import com.rag.core.entity.DocumentParseResult;
import com.rag.core.enums.ChunkStrategyEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 固定长度分片策略
 * 按固定字符长度切割，支持滑动重叠窗口
 */
public class FixedSizeChunker implements TextChunker {

    private static final Logger log = LoggerFactory.getLogger(FixedSizeChunker.class);

    @Override
    public List<Chunk> chunk(DocumentParseResult parseResult, ChunkConfig config) {
        int chunkSize = config.getFixedChunkSize() != null ? config.getFixedChunkSize() : 500;
        int overlap = config.getSlideOverlap() != null ? config.getSlideOverlap() : 50;

        String text = parseResult.getFullText();
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        List<Chunk> chunks = new ArrayList<>();
        int start = 0;
        int sequence = 0;

        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());

            // 尝试在空白处截断，避免切断词语
            if (end < text.length()) {
                int breakPoint = findBreakPoint(text, end);
                if (breakPoint > start && breakPoint <= end + overlap) {
                    end = breakPoint;
                }
            }

            String chunkText = text.substring(start, end).trim();
            if (!chunkText.isEmpty()) {
                chunks.add(Chunk.builder()
                        .chunkId(UUID.randomUUID().toString())
                        .text(chunkText)
                        .chunkType(ChunkStrategyEnum.FIXED_SIZE)
                        .textHash(hash(chunkText))
                        .fileName(parseResult.getMeta() != null ? parseResult.getMeta().getFileName() : null)
                        .fileType(parseResult.getMeta() != null ? parseResult.getMeta().getFileType() : null)
                        .fileId(parseResult.getMeta() != null ? parseResult.getMeta().getFileId() : null)
                        .build());
                sequence++;
            }

            start = end - overlap;
            if (start >= text.length()) break;
            // 防止无限循环
            if (end >= text.length()) break;
        }

        log.debug("固定长度分片完成，chunkSize={}, overlap={}, 生成{}个chunk", chunkSize, overlap, chunks.size());
        return chunks;
    }

    @Override
    public ChunkStrategyEnum getStrategy() {
        return ChunkStrategyEnum.FIXED_SIZE;
    }

    /**
     * 在文本中查找合适的断点（句号、换行等）
     */
    private int findBreakPoint(String text, int position) {
        int searchStart = Math.max(0, position - 100);
        int searchEnd = Math.min(text.length(), position + 50);
        String window = text.substring(searchStart, searchEnd);
        int relativePos = position - searchStart;

        // 优先找换行符
        int nlIndex = window.lastIndexOf('\n', relativePos);
        if (nlIndex > 0) return searchStart + nlIndex;

        // 找句号、问号、感叹号
        for (char ch : new char[]{'。', '！', '？', '.', '!', '?', '\n'}) {
            int idx = window.lastIndexOf(ch, relativePos);
            if (idx > 0) return searchStart + idx + 1;
        }

        return position;
    }

    private String hash(String text) {
        int h = 0;
        for (char c : text.toCharArray()) {
            h = 31 * h + c;
        }
        return Integer.toHexString(h);
    }
}
