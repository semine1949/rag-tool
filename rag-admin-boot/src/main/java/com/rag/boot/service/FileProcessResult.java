package com.rag.boot.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件处理结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileProcessResult {
    private String fileId;
    private String fileName;
    private Integer chunkCount;
    private Integer insertedCount;
    private Boolean success;
    private String error;
}
