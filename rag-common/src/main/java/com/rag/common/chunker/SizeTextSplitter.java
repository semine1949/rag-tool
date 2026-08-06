package com.rag.common.chunker;

import com.rag.common.chunker.util.ChunkDocuments;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 通用文本分块策略（text_model）分片器。
 * <p>
 * 将长文本按「自然分隔符优先、硬截断兜底」的方式切分为语义完整、大小可控的文本块，
 * 用于向量检索。每个块长度不超过 maxTokens（以字符数近似）。
 * <p>
 * 核心参数（可按需覆盖，未指定时使用默认值）：
 * <ul>
 *   <li>{@code delimiter}：分隔符，默认 {@code "\n"}（换行）。文本先按该分隔符切分为若干片段（part）。</li>
 *   <li>{@code maxTokens}：单个块最大长度，默认 {@code 1024}（以字符数近似）。</li>
 *   <li>{@code chunkOverlap}：块与块之间的重叠字符数，默认 {@code 50}。
 *       仅当单个片段超过 maxTokens、需要硬截断时才生效，用于保持上下文衔接。</li>
 * </ul>
 * <p>
 * 输出每块包含 content / chunkIndex / chunkMode（"text_model"）字段，
 * 经 {@link ChunkDocuments#of} 写入 Spring AI {@link Document} 元数据。
 */
public class SizeTextSplitter extends TextSplitter {

    /** 块类型标识：通用文本分块策略 */
    public static final String CHUNK_MODE = "text_model";

    /** 分隔符，默认换行 */
    private String delimiter = "\n";
    /** 单个块最大长度（字符数近似 token），默认 1024 */
    private int maxTokens = 1024;
    /** 硬截断时的重叠字符数，默认 50 */
    private int chunkOverlap = 50;

    public SizeTextSplitter() {
    }

    /**
     * 全参构造，可按需覆盖默认参数。
     *
     * @param delimiter    分隔符，文本先按该分隔符切分为若干片段
     * @param maxTokens    单个块最大长度（以字符数近似）
     * @param chunkOverlap 硬截断时的重叠字符数
     */
    public SizeTextSplitter(String delimiter, int maxTokens, int chunkOverlap) {
        this.delimiter = delimiter;
        this.maxTokens = maxTokens;
        this.chunkOverlap = chunkOverlap;
    }

    // ===== 参数 getter（v3：供参数快照提取） =====

    /** 分隔符 */
    public String getDelimiter() { return delimiter; }

    /** 单个块最大长度 */
    public int getMaxTokens() { return maxTokens; }

    /** 硬截断时的重叠字符数 */
    public int getChunkOverlap() { return chunkOverlap; }

    /**
     * 满足 {@link TextSplitter} 抽象方法要求：原样返回。
     * <p>主逻辑在 {@link #apply(List)} 中完成，此方法不会被框架默认调用路径走到。</p>
     */
    @Override
    protected List<String> splitText(String text) {
        return List.of(text);
    }

    /**
     * 切分主逻辑，严格按通用文本分块策略执行。
     *
     * @param documents 输入 Document 列表（通常只有一个承载全文的 Document）
     * @return 切分后的文本块列表，每块包含 content / chunkIndex / chunkMode
     */
    @Override
    public List<Document> apply(List<Document> documents) {
        String text = ChunkDocuments.fullText(documents);
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        Document input = documents.get(0);
        List<Document> chunks = new ArrayList<>();

        // ==== 算法步骤 1：全文不超过 maxTokens，直接作为单个块 ====
        if (text.length() <= maxTokens) {
            chunks.add(buildChunk(input, text, 0));
            return chunks;
        }

        // ==== 算法步骤 2：按 delimiter（正则转义原样匹配）切分为片段 ====
        List<String> parts = splitByDelimiter(text, delimiter);
        if (parts.isEmpty()) {
            return List.of();
        }

        int chunkIndex = 0;
        StringBuilder current = new StringBuilder();

        for (String part : parts) {
            // 尝试将当前片段拼入当前块（需在片段间补回 delimiter）
            int delimiterLen = (current.length() > 0) ? delimiter.length() : 0;
            if (current.length() + delimiterLen + part.length() <= maxTokens) {
                if (current.length() > 0) {
                    current.append(delimiter);
                }
                current.append(part);
                continue;
            }

            // ==== 算法步骤 3a：保存当前块，开启新块 ====
            if (current.length() > 0) {
                chunks.add(buildChunk(input, current.toString(), chunkIndex++));
            }

            // ==== 算法步骤 3b：片段自身超过 maxTokens，做硬截断 ====
            if (part.length() > maxTokens) {
                int offset = 0;
                boolean firstHard = true;
                while (offset < part.length()) {
                    String seg;
                    if (firstHard) {
                        // 第一个硬截断块：直接截取 maxTokens
                        seg = part.substring(offset, Math.min(offset + maxTokens, part.length()));
                        firstHard = false;
                    } else {
                        // 从第二个硬截断块起，向前回溯 chunkOverlap 个字符作为重叠前缀
                        int start = offset - chunkOverlap;
                        if (start < 0) {
                            start = 0;
                        }
                        seg = part.substring(start, Math.min(start + maxTokens, part.length()));
                    }
                    if (!seg.isEmpty()) {
                        chunks.add(buildChunk(input, seg, chunkIndex++));
                    }
                    offset += maxTokens;
                }
                continue;
            }

            // ==== 算法步骤 3c：以该片段作为新块起点 ====
            current = new StringBuilder(part);
        }

        // ==== 算法步骤 4：循环结束后保存最后一个块 ====
        if (current.length() > 0) {
            chunks.add(buildChunk(input, current.toString(), chunkIndex));
        }

        return chunks;
    }

    /**
     * 按分隔符切分文本（对分隔符做正则转义，保证原样匹配）。
     * 使用 {@link Pattern#quote} 转义，避免特殊字符（如 . * + 等）被当作正则元字符。
     * 保留空片段，避免丢失空行等分隔信息。
     */
    private List<String> splitByDelimiter(String text, String delimiter) {
        String[] raw = text.split(Pattern.quote(delimiter), -1);
        List<String> parts = new ArrayList<>(raw.length);
        for (String s : raw) {
            parts.add(s);
        }
        return parts;
    }

    /**
     * 构建单个分块 Document，写入 chunkIndex / chunkMode 元数据。
     *
     * @param input      输入 Document，用于继承业务元数据
     * @param text       分块文本
     * @param chunkIndex 分块序号（从 0 开始）
     * @return 分块 Document
     */
    private Document buildChunk(Document input, String text, int chunkIndex) {
        Map<String, Object> extra = new HashMap<>();
        extra.put("chunkIndex", chunkIndex);
        extra.put("chunkMode", CHUNK_MODE);
        return ChunkDocuments.of(input, text, CHUNK_MODE, extra);
    }
}
