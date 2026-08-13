package com.rag.controller;

import lombok.Data;

import java.util.List;

/**
 * 多知识库检索 + 白名单过滤请求体。
 * <p>
 * 采用「物理隔离 + 逻辑联合 + 应用层后过滤」方案：串行遍历多个知识库召回候选，
 * 合并为统一候选池后，在应用层执行白名单过滤、跨库去重、全局精排。
 * </p>
 */
@Data
public class MultiSearchRequest {

    /** 知识库 ID 列表（必填，非空），物理隔离，各自对应独立向量集合 */
    private List<Long> kbIds;

    /** 检索内容（必填），复用同一 query 检索所有知识库 */
    private String query;

    /** 最终返回条数，默认 5 */
    private Integer topK = 5;

    /** 文档白名单（docId 列表，可空），非空时仅保留命中白名单文档的切片 */
    private List<String> whitelist;

    /** 召回放大倍数，普通场景每库召回量 = topK × expandFactor，默认 2 */
    private Integer expandFactor = 2;

    /** 检索模式：VECTOR_ONLY / BM25_ONLY / HYBRID，默认 VECTOR_ONLY */
    private String searchMode;

    /** 是否启用全局精排（默认关闭） */
    private Boolean rerank = false;

    /** 精排候选倍数（rerank=true 时生效，默认 3） */
    private Integer rerankMultiplier = 3;

    /** 是否启用父子增强（子块命中时替换为父块完整内容，默认关闭） */
    private Boolean enableParentEnhancement = false;
}
