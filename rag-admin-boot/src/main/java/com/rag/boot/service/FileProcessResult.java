package com.rag.boot.service;

import lombok.Builder;
import lombok.Data;

/**
 * 文件处理结果 / 检索结果
 */
@Data
@Builder
public class FileProcessResult {

    /** 文档ID（=解析阶段生成的 fileId） */
    private String fileId;

    /** 文件名 */
    private String fileName;

    /** 切片数量 */
    private Integer chunkCount;

    /** 入库切片数量 */
    private Integer insertedCount;

    /** 是否成功 */
    private Boolean success;

    /** 失败信息 */
    private String message;

    /** 检索命中时的文本片段 */
    private String snippet;

    /** 检索相似度得分 */
    private Double score;

    /** 文档版本 */
    private String documentVersion;
}
