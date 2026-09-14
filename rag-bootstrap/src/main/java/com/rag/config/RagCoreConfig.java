package com.rag.config;

import com.rag.auth.jwt.JwtTokenProvider;
import com.rag.auth.mapper.*;
import com.rag.auth.service.*;
import com.rag.interceptor.JwtAuthInterceptor;
import com.rag.common.chunker.ChunkStrategyFactory;
import com.rag.common.entity.config.EmbeddingProperties;
import com.rag.common.client.OpenAiClient;
import com.rag.config.factory.VectorStoreRegistry;
import com.rag.config.properties.AiModelProperties;
import com.rag.chat.config.ChatProperties;
import com.rag.common.parser.DocumentParseFactory;
import com.rag.common.parser.impl.*;
import org.mybatis.spring.annotation.MapperScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
 * 核心Bean配置（Spring AI 重构版）。
 * <p>
 * 使用统一的 AI 模型工厂（{@code AiModelFactory}）管理 Embedding / Rerank 等模型创建，
 * OCR 继续由 {@link OpenAiClient} 直连；适配器（OpenAI/Ollama/DashScope）通过 {@code @Component} 自动注册。
 * </p>
 */
@Configuration
@EnableConfigurationProperties({AiModelProperties.class, ChatProperties.class})
@MapperScan("com.rag.auth.mapper")
public class RagCoreConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(RagCoreConfig.class);

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

    // ==================== 统一 OpenAI 兼容客户端 ====================

    /**
     * 统一 OpenAI 兼容协议客户端 Bean。
     * <p>
     * 封装所有大模型 HTTP 调用（Embedding / Rerank / OCR），
     * 注入到工厂类和解析器中，替代原有的多个独立客户端。
     * </p>
     */
    @Bean
    public OpenAiClient openAiClient() {
        return new OpenAiClient();
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

    /**
     * 通用 OCR 输出 token 上限（内嵌图 / 整份回退 OCR 使用）。
     * 扫描件逐页 OCR 的 token 上限见 {@link #dsOcrPageMaxTokens}。
     */
    @Value("${rag.deepseek-ocr.max-tokens:4096}")
    private int dsOcrMaxTokens;

    /**
     * 扫描件 PDF 逐页 OCR 的页输出 token 上限。
     * <p>
     * 受 DeepSeek-OCR 总上下文（输入+输出）8192 约束：单页图片输入约占 1500~2500 vision token，
     * 若输出上限设为 8192 则必然触发 HTTP 400，故默认与通用值一致取 4096。
     * </p>
     */
    @Value("${rag.deepseek-ocr.page-max-tokens:4096}")
    private int dsOcrPageMaxTokens;

    /**
     * PDF 解析器提供方：{@code mineru}（默认）或 {@code tika-mixed}。
     * <p>用于灰度与快速回退：当 MinerU 未就绪或异常时，将配置切换为 tika-mixed 即可
     * 恢复原有「Tika 文本 + 内嵌图 OCR + 扫描件逐页 OCR」能力，无需改代码重启即可生效。</p>
     */
    @Value("${rag.parser.pdf-provider:mineru}")
    private String pdfProvider;

    @Bean
    public DocumentParseFactory documentParseFactory(OpenAiClient openAiClient) {
        Map<com.rag.common.enums.FileTypeEnum, Function<File, DocumentReader>> suppliers =
                new EnumMap<>(com.rag.common.enums.FileTypeEnum.class);

        // 策略1：文本类型 → Apache Tika（纯文本抽取，不做内嵌图 OCR）
        Function<File, DocumentReader> tikaReader = f -> new TikaDocumentReader(new FileSystemResource(f));
        // 策略2：文本+图片混合文档（图文混排）→ Tika 文本提取 + 内嵌图片多模态 OCR
        Function<File, DocumentReader> mixedReader = f ->
                new TikaOcrMixedParser(openAiClient, dsOcrBaseUrl, dsOcrApiKey, dsOcrModel,
                        dsOcrTimeoutMs, dsOcrMaxTokens, dsOcrPageMaxTokens, f);
        // 策略3：纯图片 → 多模态 OCR（纯 PNG 等图片的默认解析器）
        Function<File, DocumentReader> ocrReader = f ->
                new DeepSeekOcrParser(openAiClient, dsOcrBaseUrl, dsOcrApiKey, dsOcrModel,
                        dsOcrTimeoutMs, dsOcrMaxTokens, f);
        // 策略4：Excel → 智能表格解析
        Function<File, DocumentReader> excelReader = f -> new ExcelParser(f);
        // 策略5：MinerU → PDF 默认解析器（骨架），亦作为 modelName=minerU 时的强制覆盖器
        Function<File, DocumentReader> mineruReader = f -> new MinerUParser(f);

        // 文本类型：TXT / MD / MARKDOWN / HTML / HTM
        suppliers.put(com.rag.common.enums.FileTypeEnum.TXT, tikaReader);
        suppliers.put(com.rag.common.enums.FileTypeEnum.MD, tikaReader);
        suppliers.put(com.rag.common.enums.FileTypeEnum.MARKDOWN, tikaReader);
        suppliers.put(com.rag.common.enums.FileTypeEnum.HTML, tikaReader);
        suppliers.put(com.rag.common.enums.FileTypeEnum.HTM, tikaReader);

        // 【变更】PDF：默认走 MinerU，可通过 rag.parser.pdf-provider=tika-mixed 回退
        boolean useTikaMixedForPdf = "tika-mixed".equalsIgnoreCase(pdfProvider);
        Function<File, DocumentReader> pdfReader = useTikaMixedForPdf ? mixedReader : mineruReader;
        suppliers.put(com.rag.common.enums.FileTypeEnum.PDF, pdfReader);
        log.info("PDF 解析器路由: rag.parser.pdf-provider={} → {}",
                pdfProvider, useTikaMixedForPdf ? "TikaOcrMixedParser" : "MinerUParser");

        // 【不变】图文混排：DOC / DOCX / PPT / PPTX → Tika 文本提取 + 内嵌图 OCR
        suppliers.put(com.rag.common.enums.FileTypeEnum.DOC, mixedReader);
        suppliers.put(com.rag.common.enums.FileTypeEnum.DOCX, mixedReader);
        suppliers.put(com.rag.common.enums.FileTypeEnum.PPT, mixedReader);
        suppliers.put(com.rag.common.enums.FileTypeEnum.PPTX, mixedReader);

        // 【不变】纯图片：JPG / JPEG / PNG / BMP → DeepSeekOcrParser
        suppliers.put(com.rag.common.enums.FileTypeEnum.JPG, ocrReader);
        suppliers.put(com.rag.common.enums.FileTypeEnum.JPEG, ocrReader);
        suppliers.put(com.rag.common.enums.FileTypeEnum.PNG, ocrReader);
        suppliers.put(com.rag.common.enums.FileTypeEnum.BMP, ocrReader);

        // 【不变】Excel：XLS / XLSX → ExcelParser
        suppliers.put(com.rag.common.enums.FileTypeEnum.XLSX, excelReader);
        suppliers.put(com.rag.common.enums.FileTypeEnum.XLS, excelReader);

        return new DocumentParseFactory(suppliers, mineruReader);
    }

    // ==================== 分片策略工厂配置 ====================

    /**
     * 封装式分片策略工厂：依据 chunkStrategy + SplitterConfig 判定并构造对应 splitter。
     */
    @Bean
    public ChunkStrategyFactory chunkStrategyFactory() {
        return new ChunkStrategyFactory();
    }

    // ==================== Embedding / 向量库（Spring AI） ====================

    // 说明：Embedding 模型统一由 AiModelFactory（@Component 自动注册）创建，
    // 不再单独注册 EmbeddingModelFactory Bean。

    @Value("${rag.weaviate.url:}")
    private String weaviateUrl;

    @Value("${rag.weaviate.token:}")
    private String weaviateToken;

    @Bean
    public VectorStoreRegistry vectorStoreRegistry() {
        return new VectorStoreRegistry(weaviateUrl, weaviateToken);
    }

    // ==================== 重排模型 ====================

    // 说明：重排模型统一由 AiModelFactory.getRerankModel 创建（不走适配器），
    // 不再单独注册 RerankStrategyFactory Bean。

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
