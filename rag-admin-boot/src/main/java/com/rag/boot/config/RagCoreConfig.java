package com.rag.boot.config;

import com.rag.auth.jwt.JwtTokenProvider;
import com.rag.auth.mapper.*;
import com.rag.auth.service.*;
import com.rag.boot.interceptor.JwtAuthInterceptor;
import com.rag.chunker.ChunkerFactory;
import com.rag.chunker.ParentChildTextSplitter;
import com.rag.chunker.SizeTextSplitter;
import com.rag.chunker.impl.*;
import com.rag.core.config.EmbeddingProperties;
import com.rag.core.enums.ChunkStrategyEnum;
import com.rag.core.factory.EmbeddingModelFactory;
import com.rag.core.factory.VectorStoreRegistry;
import com.rag.parser.DocumentParseFactory;
import com.rag.parser.impl.*;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * 核心Bean配置（Spring AI 重构版）
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
        Map<com.rag.core.enums.FileTypeEnum, Function<File, DocumentReader>> suppliers =
                new EnumMap<>(com.rag.core.enums.FileTypeEnum.class);

        // 策略1：文本类型 → Apache Tika
        Function<File, DocumentReader> tikaReader = f -> new TikaDocumentReader(new FileSystemResource(f));
        // 策略2：文本+图片混合文档 → Tika 文本提取 + 内嵌图片多模态 OCR
        Function<File, DocumentReader> mixedReader = f -> new TikaOcrMixedParser(deepSeekOcrClient, f);
        // 策略3：纯图片 → 多模态 OCR
        Function<File, DocumentReader> ocrReader = f -> new DeepSeekOcrParser(deepSeekOcrClient, f);
        // 策略4：Excel → 已有 ExcelParser
        Function<File, DocumentReader> excelReader = f -> new ExcelParser(f);

        // 文本类型：TXT / MD / MARKDOWN / HTML / HTM
        suppliers.put(com.rag.core.enums.FileTypeEnum.TXT, tikaReader);
        suppliers.put(com.rag.core.enums.FileTypeEnum.MD, tikaReader);
        suppliers.put(com.rag.core.enums.FileTypeEnum.MARKDOWN, tikaReader);
        suppliers.put(com.rag.core.enums.FileTypeEnum.HTML, tikaReader);
        suppliers.put(com.rag.core.enums.FileTypeEnum.HTM, tikaReader);

        // 混合文档：PDF / DOC / DOCX / PPT / PPTX
        suppliers.put(com.rag.core.enums.FileTypeEnum.PDF, mixedReader);
        suppliers.put(com.rag.core.enums.FileTypeEnum.DOC, mixedReader);
        suppliers.put(com.rag.core.enums.FileTypeEnum.DOCX, mixedReader);
        suppliers.put(com.rag.core.enums.FileTypeEnum.PPT, mixedReader);
        suppliers.put(com.rag.core.enums.FileTypeEnum.PPTX, mixedReader);

        // 纯图片：JPG / JPEG / PNG / BMP
        suppliers.put(com.rag.core.enums.FileTypeEnum.JPG, ocrReader);
        suppliers.put(com.rag.core.enums.FileTypeEnum.JPEG, ocrReader);
        suppliers.put(com.rag.core.enums.FileTypeEnum.PNG, ocrReader);
        suppliers.put(com.rag.core.enums.FileTypeEnum.BMP, ocrReader);

        // Excel：XLS / XLSX
        suppliers.put(com.rag.core.enums.FileTypeEnum.XLSX, excelReader);
        suppliers.put(com.rag.core.enums.FileTypeEnum.XLS, excelReader);

        return new DocumentParseFactory(suppliers);
    }

    // ==================== 分片器配置 ====================

    @Bean
    public ChunkerFactory chunkerFactory() {
        Map<ChunkStrategyEnum, TextSplitter> chunkerMap = new EnumMap<>(ChunkStrategyEnum.class);
        chunkerMap.put(ChunkStrategyEnum.SEMANTIC, new SemanticChunker());
        chunkerMap.put(ChunkStrategyEnum.TABLE, new TableChunker());
        chunkerMap.put(ChunkStrategyEnum.CODE_FUNCTION, new CodeFunctionChunker());
        chunkerMap.put(ChunkStrategyEnum.TITLE_HIERARCHY, new TitleHierarchyChunker());
        chunkerMap.put(ChunkStrategyEnum.PARENT_CHILD, new ParentChildChunker());
        // 通用文本分块（text_model）与层级父子分块（hierarchical_model）使用默认参数
        chunkerMap.put(ChunkStrategyEnum.TEXT_MODEL, new SizeTextSplitter());
        chunkerMap.put(ChunkStrategyEnum.HIERARCHICAL_MODEL, new ParentChildTextSplitter());
        // FIXED_SIZE 由框架 RecursiveCharacterTextSplitter 在 ChunkerFactory 内处理
        return new ChunkerFactory(chunkerMap);
    }

    // ==================== Embedding / 向量库（Spring AI） ====================

    @Bean
    public EmbeddingModelFactory embeddingModelFactory(EmbeddingProperties embeddingProperties) {
        return new EmbeddingModelFactory(embeddingProperties);
    }

    @Value("${rag.weaviate.url:}")
    private String weaviateUrl;

    @Value("${rag.weaviate.token:}")
    private String weaviateToken;

    @Bean
    public VectorStoreRegistry vectorStoreRegistry() {
        return new VectorStoreRegistry(weaviateUrl, weaviateToken);
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
                                           VectorStoreRegistry vectorStoreRegistry,
                                           EmbeddingProperties embeddingProperties) {
        return new KbConfigService(kbMapper, vectorStoreRegistry, embeddingProperties);
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
