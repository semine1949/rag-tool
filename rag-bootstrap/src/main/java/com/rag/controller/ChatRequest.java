package com.rag.controller;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 问答（Chat）请求 DTO。
 * <p>
 * 支持三种问答模式：
 * <ul>
 *   <li>仅知识库问答：传 kbId + query</li>
 *   <li>仅上传文件问答：传 files + query</li>
 *   <li>知识库 + 上传文件混合问答：传 kbId + files + query</li>
 * </ul>
 * sessionId 为空时自动创建新会话；非空时续接已有会话（多轮对话）。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {

    /** 知识库 ID（可选，基于知识库问答时传入） */
    private Long kbId;

    /** 用户提问（必填） */
    private String query;

    /** 会话 ID（可选，多轮对话续接时传入；为空则新建会话） */
    private String sessionId;

    /** 对话模型名（可选，为空时回退全局默认 qwen-turbo） */
    private String model;

    /** 是否启用流式输出（默认 false，走同步接口） */
    @Builder.Default
    private Boolean stream = false;
}