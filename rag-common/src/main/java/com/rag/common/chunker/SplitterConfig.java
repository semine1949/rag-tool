package com.rag.common.chunker;

/**
 * 分片器参数载体（统一配置对象）。
 * <p>
 * 作为 {@link ChunkStrategyFactory#getSplitter} 的入参，按分片策略承载不同参数：
 * <ul>
 *   <li><b>text-model（SizeTextSplitter）</b>：delimiter / maxTokens / chunkOverlap</li>
 *   <li><b>hierarchical-model（ParentChildTextSplitter）</b>：parentSeparator / parentMaxTokens /
 *       childSeparator / childMaxTokens / parentMode</li>
 * </ul>
 * 所有字段均可为 {@code null}，为 null 时由具体 splitter 构造回退默认值。
 */
public class SplitterConfig {

    // ===== text-model（SizeTextSplitter）参数 =====

    /** 分隔符，默认 "\n"（换行） */
    private String delimiter;

    /** 单个块最大长度（字符数近似 token），默认 1024 */
    private Integer maxTokens;

    /** 硬截断时的重叠字符数，默认 50 */
    private Integer chunkOverlap;

    // ===== hierarchical-model（ParentChildTextSplitter）参数 =====

    /** 父块分隔符，默认 "\n\n\n"（段落边界） */
    private String parentSeparator;

    /** 父块最大长度，默认 2048 */
    private Integer parentMaxTokens;

    /** 子块分隔符，默认 "\n\n" */
    private String childSeparator;

    /** 子块最大长度，默认 1024 */
    private Integer childMaxTokens;

    /** 父块粒度模式，默认 "paragraph" */
    private String parentMode;

    /** 无参构造（所有字段为 null，由 splitter 采用默认值） */
    public SplitterConfig() {
    }

    // ==================== getter / setter ====================

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
