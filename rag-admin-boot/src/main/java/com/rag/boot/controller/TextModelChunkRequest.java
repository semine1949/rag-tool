package com.rag.boot.controller;

/**
 * text_model 上传落库请求参数 DTO。
 * <p>对应 {@code SizeTextSplitter} 参数，作为上传接口的 form 参数载体，未传入时使用默认值。</p>
 */
public class TextModelChunkRequest {

    /** 分隔符，默认 "\n"（换行），文本先按该分隔符切分为若干片段 */
    private String delimiter;

    /** 单个块最大长度（字符数近似 token），默认 1024 */
    private Integer maxTokens;

    /** 硬截断时的重叠字符数，默认 50 */
    private Integer chunkOverlap;

    public String getDelimiter() { return delimiter; }
    public void setDelimiter(String delimiter) { this.delimiter = delimiter; }

    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }

    public Integer getChunkOverlap() { return chunkOverlap; }
    public void setChunkOverlap(Integer chunkOverlap) { this.chunkOverlap = chunkOverlap; }
}
