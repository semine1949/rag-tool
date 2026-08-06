package com.rag.common.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 租户实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Tenant {
    private Long tenantId;
    private String tenantName;
    /** 租户默认 Embedding 模型名 */
    private String defaultEmbeddingModel;
    private Integer status;
    private Date createTime;
}
