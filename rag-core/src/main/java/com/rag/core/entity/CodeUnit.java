package com.rag.core.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 代码块单元
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeUnit {
    /** 代码块ID */
    private String codeId;
    /** 代码文本 */
    private String content;
    /** 编程语言 */
    private String language;
    /** 所在页码 */
    private Integer pageNo;
    /** 代码块标题/说明 */
    private String caption;
}
