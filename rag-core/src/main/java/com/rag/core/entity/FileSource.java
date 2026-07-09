package com.rag.core.entity;

import com.rag.core.enums.FileSourceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件源描述
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileSource {
    /** 文件源类型 */
    private FileSourceType sourceType;
    /** 文件路径/URL/MinIO key */
    private String path;
    /** 原始文件名 */
    private String fileName;
    /** 文件唯一标识（用于去重） */
    private String fileId;
    /** 文件内容（stream模式） */
    private byte[] content;
}
