package com.rag.boot.config;

import com.rag.chunker.ChunkerFactory;
import com.rag.chunker.impl.*;
import com.rag.core.api.EmbeddingClient;
import com.rag.core.api.TextChunker;
import com.rag.core.api.VectorStore;
import com.rag.core.enums.ChunkStrategyEnum;
import com.rag.core.enums.EmbeddingModelType;
import com.rag.embedding.EmbeddingFactory;
import com.rag.embedding.impl.*;
import com.rag.parser.DocumentParseFactory;
import com.rag.parser.impl.*;
import com.rag.weaviate.WeaviateVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 核心Bean配置 - 组装所有模块的工厂和实现
 */
@Configuration
public class RagCoreConfig {

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

    // TODO: 配置Tesseract OCR数据路径，Windows: D:/tessdata，Linux: /usr/share/tesseract-ocr/5/tessdata
    @Value("${rag.ocr.data-path:D:/tessdata}")
    private String ocrDataPath;

    // TODO: 配置OCR识别语言，多个语言用+号连接，例如 chi_sim+eng
    @Value("${rag.ocr.language:chi_sim+eng}")
    private String ocrLanguage;

    @Bean
    public DocumentParseFactory documentParseFactory() {
        PdfParser pdfParser = new PdfParser();
        OfficeParser officeParser = new OfficeParser();
        TextParser textParser = new TextParser();
        ImageOcrParser imageOcrParser = new ImageOcrParser(ocrDataPath, ocrLanguage);

        Map<com.rag.core.enums.FileTypeEnum, com.rag.core.api.DocumentParser> parserMap = Map.ofEntries(
                Map.entry(com.rag.core.enums.FileTypeEnum.PDF, pdfParser),
                Map.entry(com.rag.core.enums.FileTypeEnum.DOCX, officeParser),
                Map.entry(com.rag.core.enums.FileTypeEnum.DOC, officeParser),
                Map.entry(com.rag.core.enums.FileTypeEnum.PPTX, officeParser),
                Map.entry(com.rag.core.enums.FileTypeEnum.PPT, officeParser),
                Map.entry(com.rag.core.enums.FileTypeEnum.XLSX, officeParser),
                Map.entry(com.rag.core.enums.FileTypeEnum.XLS, officeParser),
                Map.entry(com.rag.core.enums.FileTypeEnum.TXT, textParser),
                Map.entry(com.rag.core.enums.FileTypeEnum.MD, textParser),
                Map.entry(com.rag.core.enums.FileTypeEnum.MARKDOWN, textParser),
                Map.entry(com.rag.core.enums.FileTypeEnum.HTML, textParser),
                Map.entry(com.rag.core.enums.FileTypeEnum.HTM, textParser),
                Map.entry(com.rag.core.enums.FileTypeEnum.JPG, imageOcrParser),
                Map.entry(com.rag.core.enums.FileTypeEnum.JPEG, imageOcrParser),
                Map.entry(com.rag.core.enums.FileTypeEnum.PNG, imageOcrParser),
                Map.entry(com.rag.core.enums.FileTypeEnum.BMP, imageOcrParser)
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
                EmbeddingModelType.OLLAMA, new OllamaEmbeddingClient(),
                EmbeddingModelType.OPENAI, new OpenAiEmbeddingClient(),
                EmbeddingModelType.TONGYI, new TongyiEmbeddingClient(),
                EmbeddingModelType.ONNX, new OnnxBgeEmbeddingClient()
        );
        return new EmbeddingFactory(clientMap);
    }

    // ==================== Weaviate配置 ====================

    // TODO: 填入Weaviate服务地址，默认 http://localhost:8080
    @Value("${rag.weaviate.url:http://localhost:8080}")
    private String weaviateUrl;

    // TODO: 填入Weaviate认证Token（如果启用了API Key认证），不需要则留空
    @Value("${rag.weaviate.token:}")
    private String weaviateToken;

    @Bean
    public VectorStore vectorStore() {
        return new WeaviateVectorStore(weaviateUrl, weaviateToken);
    }
}
