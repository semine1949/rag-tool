package com.rag.controller;

/**
 * 批量上传请求 DTO（Multi-Tenant v2）。
 * <p>支持在请求体中指定文档级分片策略及对应参数，Controller 依据这些参数构造
 * {@link com.rag.chunker.SplitterConfig} 传入 Service。未指定时采用默认参数。</p>
 */
public class BatchUploadRequest {
    private Long kbId;
    /** 分片策略名（text-model / hierarchical-model），可空 */
    private String chunkStrategy;
    /** 解析模型名（可选）：传 minerU 时强制使用 MinerU 解析器，覆盖扩展名路由 */
    private String modelName;

    // text-model 参数（可空）
    private String delimiter;
    private Integer maxTokens;
    private Integer chunkOverlap;

    // hierarchical-model 参数（可空）
    private String parentSeparator;
    private Integer parentMaxTokens;
    private String childSeparator;
    private Integer childMaxTokens;
    private String parentMode;

    public Long getKbId() { return kbId; }
    public void setKbId(Long kbId) { this.kbId = kbId; }

    public String getChunkStrategy() { return chunkStrategy; }
    public void setChunkStrategy(String chunkStrategy) { this.chunkStrategy = chunkStrategy; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public String getDelimiter() { return delimiter; }
    public void setDelimiter(String delimiter) { this.delimiter = delimiter; }

    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }

    public Integer getChunkOverlap() { return chunkOverlap; }
    public void setChunkOverlap(Integer chunkOverlap) { this.chunkOverlap = chunkOverlap; }

    public String getParentSeparator() { return parentSeparator; }
    public void setParentSeparator(String parentSeparator) { this.parentSeparator = parentSeparator; }

    public Integer getParentMaxTokens() { return parentMaxTokens; }
    public void setParentMaxTokens(Integer parentMaxTokens) { this.parentMaxTokens = parentMaxTokens; }

    public String getChildSeparator() { return childSeparator; }
    public void setChildSeparator(String childSeparator) { this.childSeparator = childSeparator; }

    public Integer getChildMaxTokens() { return childMaxTokens; }
    public void setChildMaxTokens(Integer childMaxTokens) { this.childMaxTokens = childMaxTokens; }

    public String getParentMode() { return parentMode; }
    public void setParentMode(String parentMode) { this.parentMode = parentMode; }
}
