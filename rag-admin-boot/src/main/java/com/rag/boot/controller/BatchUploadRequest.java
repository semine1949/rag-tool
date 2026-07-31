package com.rag.boot.controller;

/**
 * 批量上传请求 DTO（Multi-Tenant v2）
 * <p>支持在请求体中指定文档级分片策略，未指定时默认 FIXED_SIZE。</p>
 */
public class BatchUploadRequest {
    private Long kbId;
    private String chunkStrategy;   // 文档级分片策略，未指定则默认 FIXED_SIZE
    private Integer chunkSize;      // 分片大小，可空
    private Integer chunkOverlap;   // 分片重叠，可空

    public Long getKbId() { return kbId; }
    public void setKbId(Long kbId) { this.kbId = kbId; }

    public String getChunkStrategy() { return chunkStrategy; }
    public void setChunkStrategy(String chunkStrategy) { this.chunkStrategy = chunkStrategy; }

    public Integer getChunkSize() { return chunkSize; }
    public void setChunkSize(Integer chunkSize) { this.chunkSize = chunkSize; }

    public Integer getChunkOverlap() { return chunkOverlap; }
    public void setChunkOverlap(Integer chunkOverlap) { this.chunkOverlap = chunkOverlap; }
}
