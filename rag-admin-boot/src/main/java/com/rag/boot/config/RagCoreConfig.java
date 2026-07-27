package com.rag.boot.config;

import com.rag.auth.jwt.JwtTokenProvider;
import com.rag.auth.mapper.*;
import com.rag.auth.service.*;
import com.rag.boot.interceptor.JwtAuthInterceptor;
import com.rag.chunker.ChunkerFactory;
import com.rag.chunker.impl.*;
import com.rag.core.api.*;
import com.rag.core.config.EmbeddingProperties;
import com.rag.core.enums.ChunkStrategyEnum;
import com.rag.core.enums.EmbeddingModelType;
import com.rag.embedding.EmbeddingFactory;
import com.rag.embedding.impl.*;
import com.rag.parser.DocumentParseFactory;
import com.rag.parser.impl.*;
import com.rag.weaviate.WeaviateVectorStore;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 核心Bean配置
 */
@Configuration
@MapperScan("com.rag.auth.mapper")
public class RagCoreConfig implements WebMvcConfigurer {

    // ==================== 线程池配置 ====================

    @Bean("ragTaskExecutor")
    public Executor ragTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("rag-task-");
        executor.setRejectedExecutionHandler(
                new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    // ==================== 文档解析器配置 ====================

    @Value("${rag.deepseek-ocr.api-key:}")
    private String dsOcrApiKey;

    @Value("${rag.deepseek-ocr.base-url:}")
    private String dsOcrBaseUrl;

    @Value("${rag.deepseek-ocr.model:deepseek-ocr}")
    private String dsOcrModel;

    @Value("${rag.deepseek-ocr.timeout-ms:120000}")
    private long dsOcrTimeoutMs;

    @Bean
    public DeepSeekOcrClient deepSeekOcrClient() {
        return new DeepSeekOcrClient(dsOcrApiKey, dsOcrBaseUrl, dsOcrModel, dsOcrTimeoutMs);
    }

    @Bean
    public DocumentParseFactory documentParseFactory(DeepSeekOcrClient deepSeekOcrClient) {
        TextParser textParser = new TextParser();
        DeepSeekOcrParser ocrParser = new DeepSeekOcrParser(deepSeekOcrClient);

        Map<com.rag.core.enums.FileTypeEnum, com.rag.core.api.DocumentParser> parserMap = Map.ofEntries(
                Map.entry(com.rag.core.enums.FileTypeEnum.TXT, textParser),
                Map.entry(com.rag.core.enums.FileTypeEnum.MD, textParser),
                Map.entry(com.rag.core.enums.FileTypeEnum.MARKDOWN, textParser),
                Map.entry(com.rag.core.enums.FileTypeEnum.JPG, ocrParser),
                Map.entry(com.rag.core.enums.FileTypeEnum.JPEG, ocrParser),
                Map.entry(com.rag.core.enums.FileTypeEnum.PNG, ocrParser),
                Map.entry(com.rag.core.enums.FileTypeEnum.BMP, ocrParser),
                Map.entry(com.rag.core.enums.FileTypeEnum.PDF, ocrParser),
                Map.entry(com.rag.core.enums.FileTypeEnum.DOCX, ocrParser),
                Map.entry(com.rag.core.enums.FileTypeEnum.DOC, ocrParser),
                Map.entry(com.rag.core.enums.FileTypeEnum.PPTX, ocrParser),
                Map.entry(com.rag.core.enums.FileTypeEnum.PPT, ocrParser),
                Map.entry(com.rag.core.enums.FileTypeEnum.XLSX, ocrParser),
                Map.entry(com.rag.core.enums.FileTypeEnum.XLS, ocrParser),
                Map.entry(com.rag.core.enums.FileTypeEnum.HTML, ocrParser),
                Map.entry(com.rag.core.enums.FileTypeEnum.HTM, ocrParser)
        );
        return new DocumentParseFactory(parserMap);
    }

    // ==================== 分片器配置 ====================

    @Bean
    public ChunkerFactory chunkerFactory() {
        Map<ChunkStrategyEnum, TextChunker> chunkerMap = Map.of(
                ChunkStrategyEnum.FIXED_SIZE, new FixedSizeChunker(),
                ChunkStrategyEnum.SEMANTIC, new SemanticChunker(),
                ChunkStrategyEnum.TABLE, new TableChunker(),
                ChunkStrategyEnum.CODE_FUNCTION, new CodeFunctionChunker(),
                ChunkStrategyEnum.TITLE_HIERARCHY, new TitleHierarchyChunker(),
                ChunkStrategyEnum.PARENT_CHILD, new ParentChildChunker()
        );
        return new ChunkerFactory(chunkerMap);
    }

    // ==================== Embedding工厂配置 ====================

    @Bean
    public EmbeddingFactory embeddingFactory() {
        Map<EmbeddingModelType, EmbeddingClient> clientMap = Map.of(
                EmbeddingModelType.BGE_M3, new BgeM3EmbeddingClient(),
                EmbeddingModelType.TONGYI, new TongyiEmbeddingClient(),
                EmbeddingModelType.OPENAI, new OpenAiEmbeddingClient()
        );
        return new EmbeddingFactory(clientMap);
    }

    // ==================== 向量存储配置 ====================

    @Value("${rag.weaviate.url:http://localhost:8080}")
    private String weaviateUrl;

    @Value("${rag.weaviate.token:}")
    private String weaviateToken;

    @Bean
    public VectorStore vectorStore() {
        return new WeaviateVectorStore(weaviateUrl, weaviateToken);
    }

    // ==================== 密码加密 ====================

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // ==================== JWT 配置 ====================

    @Value("${rag.auth.jwt.secret:rag-tool-default-secret-key-change-in-production-min-256-bits!!}")
    private String jwtSecret;

    @Value("${rag.auth.jwt.access-expiration:7200000}")
    private long accessTokenExpiration;

    @Value("${rag.auth.jwt.refresh-expiration:604800000}")
    private long refreshTokenExpiration;

    @Bean
    public JwtTokenProvider jwtTokenProvider() {
        return new JwtTokenProvider(jwtSecret, accessTokenExpiration, refreshTokenExpiration);
    }

    // ==================== 服务层 Bean ====================

    @Bean
    public AuthServiceImpl authService(UserMapper userMapper,
                                       TenantMapper tenantMapper,
                                       UserTenantRoleMapper userTenantRoleMapper,
                                       RoleMapper roleMapper) {
        return new AuthServiceImpl(jwtTokenProvider(), passwordEncoder(),
                userMapper, tenantMapper, userTenantRoleMapper, roleMapper);
    }

    @Bean
    @ConfigurationProperties(prefix = "rag.embedding")
    public EmbeddingProperties embeddingProperties() {
        return new EmbeddingProperties();
    }

    @Bean
    public KbConfigService kbConfigService(KnowledgeBaseMapper kbMapper,
                                           VectorStore vectorStore,
                                           EmbeddingProperties embeddingProperties,
                                           KbRolePermissionMapper kbRolePermissionMapper,
                                           RoleMapper roleMapper) {
        return new KbConfigService(kbMapper, vectorStore, embeddingProperties,
                kbRolePermissionMapper, roleMapper);
    }

    @Bean
    public KbAccessService kbAccessService(KnowledgeBaseMapper kbMapper,
                                           UserTenantRoleMapper userTenantRoleMapper,
                                           KbRolePermissionMapper kbRolePermissionMapper,
                                           RoleMapper roleMapper) {
        return new KbAccessService(kbMapper, userTenantRoleMapper,
                kbRolePermissionMapper, roleMapper);
    }

    @Bean
    public VersionServiceImpl versionService(DocumentVersionMapper versionMapper) {
        return new VersionServiceImpl(versionMapper);
    }

    // ==================== JWT 拦截器 ====================

    @Bean
    public JwtAuthInterceptor jwtAuthInterceptor(JwtTokenProvider jwtTokenProvider) {
        return new JwtAuthInterceptor(jwtTokenProvider);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtAuthInterceptor(jwtTokenProvider()))
                .addPathPatterns("/**")
                .excludePathPatterns("/api/auth/**");
    }
}
