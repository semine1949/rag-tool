package com.rag.core.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 知识库文档登记实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KbDocument {
    private Long docId;
    private Long kbId;
    private Long tenantId;
    private String fileName;
    private String fileType;
    private Integer chunkCount;
    private String version;
    private Long ownerId;
    private String collectionName;
    private Date uploadTime;
}
