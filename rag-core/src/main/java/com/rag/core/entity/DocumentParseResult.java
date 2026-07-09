package com.rag.core.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 文档解析统一输出结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentParseResult {
    /** 文件完整纯文本 */
    private String fullText;
    /** 提取到的所有表格数据 */
    private List<TableUnit> tableList;
    /** 提取的代码块 */
    private List<CodeUnit> codeBlockList;
    /** 标题层级树（h1/h2/h3 父子关系） */
    private List<TitleNode> titleTree;
    /** 文件基础元数据 */
    private DocumentMeta meta;
}
