package com.rag.core.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档元数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentMeta {
    /** 文件名 */
    private String fileName;
    /** 文件类型 */
    private String fileType;
    /** 文件大小（字节） */
    private Long fileSize;
    /** 文件唯一标识 */
    private String fileId;
    /** 源路径 */
    private String sourcePath;
    /** 总页数 */
    private Integer totalPages;
    /** 创建时间 */
    private String createTime;
    /** 修改时间 */
    private String modifyTime;
}
