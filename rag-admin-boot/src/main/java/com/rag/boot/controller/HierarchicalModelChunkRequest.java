package com.rag.boot.controller;

/**
 * hierarchical_model 上传落库请求参数 DTO。
 * <p>对应 {@code ParentChildTextSplitter} 参数，作为上传接口的 form 参数载体，未传入时使用默认值。</p>
 */
public class HierarchicalModelChunkRequest {

    /** 父块分隔符，默认 "\n\n\n"，视为段落边界 */
    private String parentSeparator;

    /** 父块最大长度，默认 2048 */
    private Integer parentMaxTokens;

    /** 子块分隔符，默认 "\n\n" */
    private String childSeparator;

    /** 子块最大长度，默认 1024 */
    private Integer childMaxTokens;

    /** 父块粒度模式，固定 "paragraph"，可空 */
    private String parentMode;

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
