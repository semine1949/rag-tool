package com.rag.core.config;

import lombok.Data;

/**
 * Embedding 模型配置属性（绑定 application.yml 的 rag.embedding.*）
 * 用于根据知识库存储的模型名解析对应的客户端类型与调用凭证。
 * 该 POJO 不依赖 Spring，由 boot 模块的 @ConfigurationProperties @Bean 方法绑定。
 */
@Data
public class EmbeddingProperties {

    /** 默认模型类型：tongyi / bge-m3 / openai */
    private String type = "tongyi";

    private ModelProps tongyi = new ModelProps();
    private ModelProps bgeM3 = new ModelProps();
    private ModelProps openai = new ModelProps();

    @Data
    public static class ModelProps {
        private String apiKey;
        private String model;
        private Integer dim;
        private String baseUrl;
    }
}
