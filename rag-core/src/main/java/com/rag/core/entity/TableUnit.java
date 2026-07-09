package com.rag.core.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 表格单元
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TableUnit {
    /** 表格ID */
    private String tableId;
    /** 表格文本内容 */
    private String content;
    /** 行数 */
    private Integer rowCount;
    /** 列数 */
    private Integer colCount;
    /** 表格所在页码 */
    private Integer pageNo;
    /** 表格所在sheet名称（Excel） */
    private String sheetName;
    /** 表格标题/说明 */
    private String caption;
    /** 表头列表 */
    private List<String> headers;
}
