package com.rag.common.enums;

/**
 * 协议类型枚举，用于标识模型服务所遵循的通信协议。
 * <p>
 * 不同的协议由对应的 {@code ModelAdapter} 适配器实现来屏蔽差异，
 * 工厂通过 {@code supports(category, protocol)} 策略路由到具体适配器。
 * </p>
 *
 * @author rag-tool
 * @since 1.0
 */
public enum ProtocolType {

    /**
     * OpenAI 标准协议。
     * <p>兼容所有实现 OpenAI API 规范的模型服务（如硅基流动、DeepSeek、通义兼容端点等）。</p>
     */
    OPENAI,

    /**
     * Ollama 本地模型部署协议。
     * <p>面向本地化部署的大模型服务。</p>
     */
    OLLAMA,

    /**
     * 阿里云 DashScope 协议（通义千问 / 百炼平台）。
     * <p>由 spring-ai-alibaba 体系提供官方 SDK 支持。</p>
     */
    DASHSCOPE
}
