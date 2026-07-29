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
 * 父子分片策略：父块为大窗口、子块为小窗口，子块通过 parentId 关联父块。
 * 实现 Spring AI {@link ConfigurableTextSplitter}，产出 {@link Document} 列表。
 */
public class ParentChildChunker extends ConfigurableTextSplitter {

    private static final Logger log = LoggerFactory.getLogger(ParentChildChunker.class);
    private ChunkConfig config;

    @Override
    public void setConfig(ChunkConfig config) {
        this.config = config;
    }

    @Override
    public List<Document> apply(List<Document> documents) {
        String text = ChunkDocuments.fullText(documents);
        if (text.isEmpty()) {
            return List.of();
        }
        int parentLen = config != null && config.getParentChunkLen() != null ? config.getParentChunkLen() : 1000;
        int childLen = config != null && config.getChildChunkLen() != null ? config.getChildChunkLen() : 200;
        Document input = documents.get(0);

        List<Document> chunks = new ArrayList<>();
        List<Document> parents = splitFixed(input, text, parentLen, parentLen / 4, "PARENT");
        List<Document> children = splitFixed(input, text, childLen, childLen / 4, "CHILD");

        for (Document parent : parents) {
            chunks.add(parent);
            String parentId = parent.getId();
            for (Document child : children) {
                if (child.getText() != null && parent.getText().contains(child.getText())) {
                    child.getMetadata().put("parentId", parentId);
                }
            }
        }
        chunks.addAll(children);

        log.debug("父子分片完成，父块{}个，子块{}个", parents.size(), children.size());
        return chunks;
    }

    private List<Document> splitFixed(Document input, String text, int chunkSize, int overlap, String role) {
        List<Document> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            if (end < text.length()) {
                int bp = findBreakPoint(text, end);
                if (bp > start && bp <= end + overlap) {
                    end = bp;
                }
            }
            String chunkText = text.substring(start, end).trim();
            if (!chunkText.isEmpty()) {
                Map<String, Object> extra = new HashMap<>();
                extra.put("chunkRole", role);
                chunks.add(ChunkDocuments.of(input, chunkText, ChunkStrategyEnum.PARENT_CHILD.name(), extra));
            }
            start = end - overlap;
            if (start >= text.length() || end >= text.length()) {
                break;
            }
        }
        return chunks;
    }

    private int findBreakPoint(String text, int position) {
        int searchStart = Math.max(0, position - 100);
        String window = text.substring(searchStart, Math.min(text.length(), position + 50));
        int relativePos = position - searchStart;
        for (char ch : new char[]{'。', '！', '？', '.', '!', '?', '\n'}) {
            int idx = window.lastIndexOf(ch, relativePos);
            if (idx > 0) {
                return searchStart + idx + 1;
            }
        }
        return position;
    }
}
