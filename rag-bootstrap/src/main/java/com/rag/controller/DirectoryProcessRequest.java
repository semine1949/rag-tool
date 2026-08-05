package com.rag.controller;

/**
 * 目录处理请求 DTO（Multi-Tenant v2）
 */
public class DirectoryProcessRequest {
    private String dirPath;
    private Long kbId;

    public String getDirPath() { return dirPath; }
    public void setDirPath(String dirPath) { this.dirPath = dirPath; }

    public Long getKbId() { return kbId; }
    public void setKbId(Long kbId) { this.kbId = kbId; }
}
