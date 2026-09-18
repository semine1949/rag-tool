package com.rag.config;

import com.rag.auth.jwt.JwtTokenProvider;
import com.rag.auth.mapper.*;
import com.rag.auth.service.*;
import com.rag.interceptor.JwtAuthInterceptor;
import com.rag.common.chunker.ChunkStrategyFactory;
import com.rag.common.client.MinerUClient;
import com.rag.common.client.MinerUOptions;
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

    /**
     * 大模型统一配置（spring.ai.platform.models，来自 application.public.yml）。
     * <p>OCR 等模型凭证与 extensions 运行参数均由此读取，替代原多个 @Value 直连配置项。</p>
     */
    private final AiModelProperties aiModelProperties;

    public RagCoreConfig(AiModelProperties aiModelProperties) {
        this.aiModelProperties = aiModelProperties;
    }

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

    /** OCR 模型在 {@code spring.ai.platform.models} 中的逻辑名（与 application.public.yml 一致） */
    private static final String OCR_MODEL_LOGICAL_NAME = "deepseek-ocr";

    /** OCR 请求超时默认值（毫秒），模型未配置 extensions.timeout-ms 时生效 */
    private static final long DEFAULT_OCR_TIMEOUT_MS = 120000L;

    /** 通用 OCR 输出 token 上限默认值，模型未配置 extensions.max-tokens 时生效 */
    private static final int DEFAULT_OCR_MAX_TOKENS = 4096;

    /** 扫描件 PDF 逐页 OCR 页输出 token 上限默认值，模型未配置 extensions.page-max-tokens 时生效 */
    private static final int DEFAULT_OCR_PAGE_MAX_TOKENS = 4096;

    /**
     * 读取 OCR 模型的配置项。
     * <p>
     * 模型凭证与扩展参数统一声明在 {@code application.public.yml} 的
     * {@code spring.ai.platform.models.deepseek-ocr} 下，本方法负责从
     * {@link AiModelProperties} 取值并按需回退默认值（配置缺失仅降级，不阻断启动）。
     * </p>
     *
     * @return OCR 模型配置快照
     */
    private OcrModelOptions resolveOcrModelOptions() {
        AiModelProperties.ModelConfig cfg = aiModelProperties == null || aiModelProperties.getModels() == null
                ? null : aiModelProperties.getModels().get(OCR_MODEL_LOGICAL_NAME);
        if (cfg == null) {
            log.warn("未找到 OCR 模型配置 spring.ai.platform.models.{}, 使用默认运行参数",
                    OCR_MODEL_LOGICAL_NAME);
            return new OcrModelOptions("", "", OCR_MODEL_LOGICAL_NAME,
                    DEFAULT_OCR_TIMEOUT_MS, DEFAULT_OCR_MAX_TOKENS, DEFAULT_OCR_PAGE_MAX_TOKENS);
        }
        String baseUrl = cfg.getBaseUrl() == null ? "" : cfg.getBaseUrl();
        String apiKey = cfg.getApiKey() == null ? "" : cfg.getApiKey();
        String model = cfg.getModelName() == null ? OCR_MODEL_LOGICAL_NAME : cfg.getModelName();

        // 总上下文约束说明：DeepSeek-OCR 输入+输出共 8192，单页图片输入约占 1500~2500 vision token，
        // 故页输出上限取 4096（4096 + 图片输入 ≈ 5500~6600 < 8192），不可设为 8192，否则服务端返回 HTTP 400。
        long timeoutMs = cfg.getExtensionLong("timeout-ms", DEFAULT_OCR_TIMEOUT_MS);
        int maxTokens = cfg.getExtensionInt("max-tokens", DEFAULT_OCR_MAX_TOKENS);
        int pageMaxTokens = cfg.getExtensionInt("page-max-tokens", DEFAULT_OCR_PAGE_MAX_TOKENS);
        log.info("OCR 模型配置: logicalName={}, model={}, baseUrl={}, timeoutMs={}, maxTokens={}, pageMaxTokens={}",
                OCR_MODEL_LOGICAL_NAME, model, baseUrl, timeoutMs, maxTokens, pageMaxTokens);
        return new OcrModelOptions(baseUrl, apiKey, model, timeoutMs, maxTokens, pageMaxTokens);
    }

    /**
     * OCR 模型配置快照（值对象），由 {@link #resolveOcrModelOptions()} 构造后透传给解析器。
     *
     * @param baseUrl       API 基础地址
     * @param apiKey        鉴权密钥
     * @param model         调用时使用的模型名
     * @param timeoutMs     单次请求超时（毫秒）
     * @param maxTokens     通用输出 token 上限
     * @param pageMaxTokens 扫描件逐页 OCR 的页输出 token 上限
     */
    private record OcrModelOptions(String baseUrl, String apiKey, String model,
                                   long timeoutMs, int maxTokens, int pageMaxTokens) {
    }

    /** MinerU 解析服务在 {@code spring.ai.platform.models} 中的逻辑名（与 application.public.yml 一致） */
    private static final String MINERU_MODEL_LOGICAL_NAME = "mineru";

    /** MinerU 单次 HTTP 超时默认值（毫秒），模型未配置 extensions.timeout-ms 时生效 */
    private static final long DEFAULT_MINERU_TIMEOUT_MS = 60_000L;

    /** MinerU 轮询间隔默认值（毫秒），模型未配置 extensions.poll-interval-ms 时生效 */
    private static final long DEFAULT_MINERU_POLL_INTERVAL_MS = 5_000L;

    /** MinerU 最大轮询次数默认值，与轮询间隔共同构成总等待上限（默认 5s × 120 = 10 分钟） */
    private static final int DEFAULT_MINERU_MAX_POLL_COUNT = 120;

    /** MinerU 模型版本默认值（vlm 对版面与公式的还原效果更好） */
    private static final String DEFAULT_MINERU_MODEL_VERSION = "vlm";

    /** MinerU 文档语言默认值 */
    private static final String DEFAULT_MINERU_LANGUAGE = "ch";

    /**
     * 读取 MinerU 解析服务的配置项，并封装为 {@link MinerUOptions} 值对象。
     * <p>
     * 配置统一声明在 {@code application.public.yml} 的
     * {@code spring.ai.platform.models.mineru}（含 {@code extensions} 运行参数），
     * 本方法负责取值并按需回退默认值（配置缺失仅降级，不阻断启动）。
     * </p>
     * <p>
     * 返回值直接注入 {@link com.rag.common.client.MinerUClient} 并由其持有，
     * 使解析器构造器与方法签名只需接收数据参数。
     * </p>
     *
     * @return MinerU 配置参数快照
     */
    private MinerUOptions resolveMineruOptions() {
        AiModelProperties.ModelConfig cfg = aiModelProperties == null || aiModelProperties.getModels() == null
                ? null : aiModelProperties.getModels().get(MINERU_MODEL_LOGICAL_NAME);
        if (cfg == null) {
            log.warn("未找到 MinerU 配置 spring.ai.platform.models.{}, 使用默认运行参数", MINERU_MODEL_LOGICAL_NAME);
            return new MinerUOptions("", "", DEFAULT_MINERU_MODEL_VERSION, DEFAULT_MINERU_LANGUAGE,
                    true, true, DEFAULT_MINERU_TIMEOUT_MS,
                    DEFAULT_MINERU_POLL_INTERVAL_MS, DEFAULT_MINERU_MAX_POLL_COUNT);
        }
        String baseUrl = cfg.getBaseUrl() == null ? "" : cfg.getBaseUrl();
        String apiKey = cfg.getApiKey() == null ? "" : cfg.getApiKey();
        String modelVersion = cfg.getExtensionString("model-version", DEFAULT_MINERU_MODEL_VERSION);
        String language = cfg.getExtensionString("language", DEFAULT_MINERU_LANGUAGE);
        boolean enableFormula = !"false".equalsIgnoreCase(cfg.getExtensionString("enable-formula", "true"));
        boolean enableTable = !"false".equalsIgnoreCase(cfg.getExtensionString("enable-table", "true"));
        long timeoutMs = cfg.getExtensionLong("timeout-ms", DEFAULT_MINERU_TIMEOUT_MS);
        long pollIntervalMs = cfg.getExtensionLong("poll-interval-ms", DEFAULT_MINERU_POLL_INTERVAL_MS);
        int maxPollCount = cfg.getExtensionInt("max-poll-count", DEFAULT_MINERU_MAX_POLL_COUNT);

        MinerUOptions options = new MinerUOptions(baseUrl, apiKey, modelVersion, language,
                enableFormula, enableTable, timeoutMs, pollIntervalMs, maxPollCount);
        // 日志由 MinerUOptions.toString() 输出，已对 api-key 做脱敏
        log.info("MinerU 配置: logicalName={}, {}", MINERU_MODEL_LOGICAL_NAME, options);
        return options;
    }

    /**
     * MinerU 客户端 Bean。
     * <p>配置在构造期注入一次并全程复用——这是「参数封装」策略的落地：
     * 解析器（每个文件 new 一次）只需接收该客户端与文件两个参数。</p>
     *
     * @return MinerU 官方 API v4 客户端
     */
    @Bean
    public MinerUClient mineruClient() {
        return new MinerUClient(resolveMineruOptions());
    }

    /**
     * PDF 解析器提供方：{@code mineru}（默认）或 {@code tika-mixed}。
     * <p>用于灰度与快速回退：当 MinerU 未就绪或异常时，将配置切换为 tika-mixed 即可
     * 恢复原有「Tika 文本 + 内嵌图 OCR + 扫描件逐页 OCR」能力，无需改代码重启即可生效。</p>
     */
    @Value("${rag.parser.pdf-provider:mineru}")
    private String pdfProvider;

    @Bean
    public DocumentParseFactory documentParseFactory(OpenAiClient openAiClient, MinerUClient mineruClient) {
        Map<com.rag.common.enums.FileTypeEnum, Function<File, DocumentReader>> suppliers =
                new EnumMap<>(com.rag.common.enums.FileTypeEnum.class);

        // OCR 模型配置统一从 spring.ai.platform.models.deepseek-ocr 读取（含 extensions 运行参数）
        OcrModelOptions ocr = resolveOcrModelOptions();

        // 策略1：文本类型 → Apache Tika（纯文本抽取，不做内嵌图 OCR）
        Function<File, DocumentReader> tikaReader = f -> new TikaDocumentReader(new FileSystemResource(f));
        // 策略2：文本+图片混合文档（图文混排）→ Tika 文本提取 + 内嵌图片多模态 OCR
        Function<File, DocumentReader> mixedReader = f ->
                new TikaOcrMixedParser(openAiClient, ocr.baseUrl(), ocr.apiKey(), ocr.model(),
                        ocr.timeoutMs(), ocr.maxTokens(), ocr.pageMaxTokens(), f);
        // 策略3：纯图片 → 多模态 OCR（纯 PNG 等图片的默认解析器）
        Function<File, DocumentReader> ocrReader = f ->
                new DeepSeekOcrParser(openAiClient, ocr.baseUrl(), ocr.apiKey(), ocr.model(),
                        ocr.timeoutMs(), ocr.maxTokens(), f);
        // 策略4：Excel → 智能表格解析
        Function<File, DocumentReader> excelReader = f -> new ExcelParser(f);
        // 策略5：MinerU → PDF 默认解析器（远程 MinerU 官方 API），亦作为 modelName=minerU 时的强制覆盖器
        // 注：配置参数已封装在 MinerUClient 持有的 MinerUOptions 中，故此处仅传「客户端 + 文件」两项
        Function<File, DocumentReader> mineruReader = f -> new MinerUParser(mineruClient, f);

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

    /**
     * 知识库配置服务：Embedding 配置统一由 {@link AiModelProperties}
     * （spring.ai.platform.models + extensions.dim）提供，不再依赖独立的 rag.embedding 段。
     */
    @Bean
    public KbConfigService kbConfigService(KnowledgeBaseMapper kbMapper,
                                           VectorStoreRegistry vectorStoreRegistry,
                                           AiModelProperties aiModelProperties) {
        return new KbConfigService(kbMapper, vectorStoreRegistry, aiModelProperties);
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
