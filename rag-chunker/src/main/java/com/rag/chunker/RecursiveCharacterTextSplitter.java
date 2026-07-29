package com.rag.chunker;

import com.rag.chunker.util.ChunkDocuments;
import com.rag.core.config.ChunkConfig;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 递归字符分片器（固定长度策略）。
 * <p>
 * 框架 {@code spring-ai-commons} 仅提供 {@code TextSplitter}/{@code TokenTextSplitter}，
 * 未包含 {@code RecursiveCharacterTextSplitter}，此处基于 {@link TextSplitter} 自行实现：
 * 按 {@code ["\n\n", "\n", "。", ".", " ", ""]} 逐级递归切分，再按 chunkSize / overlap 合并。
 */
public class RecursiveCharacterTextSplitter extends ConfigurableTextSplitter {

    private static final List<String> DEFAULT_SEPARATORS =
            List.of("\n\n", "\n", "。", ".", " ", "");

    private int chunkSize = 500;
    private int overlap = 50;

    public RecursiveCharacterTextSplitter() {
    }

    public RecursiveCharacterTextSplitter(int chunkSize, int overlap) {
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    @Override
    public void setConfig(ChunkConfig config) {
        super.setConfig(config);
        if (config.getFixedChunkSize() != null) {
            this.chunkSize = config.getFixedChunkSize();
        }
        if (config.getSlideOverlap() != null) {
            this.overlap = config.getSlideOverlap();
        }
    }

    @Override
    public List<Document> apply(List<Document> documents) {
        List<Document> chunks = new ArrayList<>();
        for (Document doc : documents) {
            String text = doc.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            for (String piece : split(text)) {
                chunks.add(ChunkDocuments.of(doc, piece, "fixed", new HashMap<>()));
            }
        }
        return chunks;
    }

    private List<String> split(String text) {
        List<String> pieces = recursiveSplit(text, 0);
        List<String> result = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (String piece : pieces) {
            if (sb.length() > 0 && sb.length() + 1 + piece.length() > chunkSize) {
                result.add(sb.toString());
                sb = new StringBuilder(tail(sb.toString(), overlap));
            }
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(piece);
            if (sb.length() > chunkSize) {
                result.add(sb.substring(0, chunkSize));
                sb = new StringBuilder(sb.substring(chunkSize));
            }
        }
        if (sb.length() > 0) {
            result.add(sb.toString());
        }
        return result;
    }

    private List<String> recursiveSplit(String text, int index) {
        if (text.length() <= chunkSize) {
            return List.of(text);
        }
        if (index >= DEFAULT_SEPARATORS.size()) {
            // 退化为按字符硬切
            List<String> hard = new ArrayList<>();
            for (int i = 0; i < text.length(); i += chunkSize) {
                hard.add(text.substring(i, Math.min(i + chunkSize, text.length())));
            }
            return hard;
        }
        String sep = DEFAULT_SEPARATORS.get(index);
        List<String> parts = splitBySeparator(text, sep);
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            if (part.length() <= chunkSize) {
                result.add(part);
            } else {
                result.addAll(recursiveSplit(part, index + 1));
            }
        }
        return result;
    }

    private List<String> splitBySeparator(String text, String sep) {
        List<String> parts = new ArrayList<>();
        if (sep.isEmpty()) {
            parts.add(text);
            return parts;
        }
        int from = 0;
        int idx;
        while ((idx = text.indexOf(sep, from)) >= 0) {
            parts.add(text.substring(from, idx));
            from = idx + sep.length();
        }
        parts.add(text.substring(from));
        return parts;
    }

    private static String tail(String s, int n) {
        if (s.length() <= n) {
            return s;
        }
        return s.substring(s.length() - n);
    }
}
