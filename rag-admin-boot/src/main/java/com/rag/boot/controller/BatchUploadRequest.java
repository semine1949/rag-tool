package com.rag.boot.controller;

/**
 * 批量上传请求 DTO（Multi-Tenant v2）
 */
public class BatchUploadRequest {
    private Long kbId;

    public Long getKbId() { return kbId; }
    public void setKbId(Long kbId) { this.kbId = kbId; }
}
