package com.rag.common.chunker.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 分片结果辅助工具：将原本的 {@code Chunk} 转换为 Spring AI {@link Document}。
 * <p>
 * 业务元数据（fileName/fileType/fileId/sourcePath 等）从输入 Document 继承，
 * 并补充 chunkType / textHash；嵌套 Map/List 统一序列化为 JSON 字符串，
 * 确保可安全写入向量库元数据。
 */
public final class ChunkDocuments {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ChunkDocuments() {
    }

    /**
     * 将多个输入 Document 的全文合并为单一文本（解析阶段通常只有一个承载全文的 Document）。
     */
    public static String fullText(List<Document> documents) {
        return documents.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * 构建单个切片 Document。
     */
    public static Document of(Document input, String text, String chunkType, Map<String, Object> extra) {
        Map<String, Object> meta = new HashMap<>(input.getMetadata());
        // 规范化已存在的非基本类型元数据，避免向量库存储异常
        for (Map.Entry<String, Object> e : new HashMap<>(meta).entrySet()) {
            if (!isPrimitive(e.getValue())) {
                meta.put(e.getKey(), toJson(e.getValue()));
            }
        }
        meta.put("chunkType", chunkType);
        meta.put("textHash", hash(text));
        if (extra != null) {
            for (Map.Entry<String, Object> e : extra.entrySet()) {
                meta.put(e.getKey(), isPrimitive(e.getValue()) ? e.getValue() : toJson(e.getValue()));
            }
        }
        return new Document(UUID.randomUUID().toString(), text, meta);
    }

    private static boolean isPrimitive(Object v) {
        return v == null || v instanceof String || v instanceof Number || v instanceof Boolean;
    }

    private static String toJson(Object v) {
        try {
            return MAPPER.writeValueAsString(v);
        } catch (JsonProcessingException e) {
            return String.valueOf(v);
        }
    }

    public static String hash(String text) {
        int h = 0;
        for (char c : text.toCharArray()) {
            h = 31 * h + c;
        }
        return Integer.toHexString(h);
    }
}
