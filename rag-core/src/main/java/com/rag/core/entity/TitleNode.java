package com.rag.core.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 标题树节点
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TitleNode {
    /** 标题ID */
    private String titleId;
    /** 标题文本 */
    private String text;
    /** 标题级别（1=h1, 2=h2, ...） */
    private Integer level;
    /** 在原文中的位置偏移 */
    private Integer position;
    /** 子标题列表 */
    private List<TitleNode> children;
    /** 父标题ID */
    private String parentId;
}
