package com.rag.chunker;

import com.rag.core.config.ChunkConfig;
import com.rag.core.enums.ChunkStrategyEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;

import java.util.*;

/**
 * 分片工厂（协调器）：按启用策略组合切割。
 * <p>
 * 执行顺序：先分离表格 / 代码块单独分片 → 正文按标题分层切割 →
 * 语义 / 固定长度切割（固定长度使用框架 {@link RecursiveCharacterTextSplitter}）→ 父子关联分片。
 */
public class ChunkerFactory {

    private static final Logger log = LoggerFactory.getLogger(ChunkerFactory.class);

    private final Map<ChunkStrategyEnum, TextSplitter> chunkerMap;

    public ChunkerFactory(Map<ChunkStrategyEnum, TextSplitter> chunkerMap) {
        this.chunkerMap = chunkerMap;
    }

    public List<Document> chunk(List<Document> documents, ChunkConfig config) {
        Set<ChunkStrategyEnum> strategies = config.getEnableStrategies();
        if (strategies == null || strategies.isEmpty()) {
            log.warn("未配置分片策略，使用默认固定长度分片");
            strategies = Set.of(ChunkStrategyEnum.FIXED_SIZE);
        }

        List<Document> allChunks = new ArrayList<>();

        // 注入知识库级配置
        for (TextSplitter s : chunkerMap.values()) {
            if (s instanceof ConfigurableTextSplitter c) {
                c.setConfig(config);
            }
        }

        // 第一阶段：表格、代码块
        allChunks.addAll(run(strategies, ChunkStrategyEnum.TABLE, documents));
        allChunks.addAll(run(strategies, ChunkStrategyEnum.CODE_FUNCTION, documents));

        // 第二阶段：标题分层（优先），否则语义，否则固定长度
        List<Document> titleChunks = run(strategies, ChunkStrategyEnum.TITLE_HIERARCHY, documents);
        List<Document> bodyChunks;
        if (!titleChunks.isEmpty()) {
            bodyChunks = titleChunks;
        } else {
            List<Document> sem = run(strategies, ChunkStrategyEnum.SEMANTIC, documents);
            bodyChunks = sem.isEmpty() ? runFixed(strategies, documents, config) : sem;
        }
        allChunks.addAll(bodyChunks);

        // 第四阶段：父子分片
        allChunks.addAll(run(strategies, ChunkStrategyEnum.PARENT_CHILD, documents));

        log.info("分片完成，共生成 {} 个Chunk", allChunks.size());
        return allChunks;
    }

    private List<Document> run(Set<ChunkStrategyEnum> strategies, ChunkStrategyEnum strategy,
                               List<Document> documents) {
        if (!strategies.contains(strategy)) {
            return List.of();
        }
        TextSplitter splitter = chunkerMap.get(strategy);
        if (splitter == null) {
            return List.of();
        }
        try {
            return splitter.apply(documents);
        } catch (Exception e) {
            log.error("分片策略 {} 执行失败: {}", strategy, e.getMessage(), e);
            return List.of();
        }
    }

    private List<Document> runFixed(Set<ChunkStrategyEnum> strategies, List<Document> documents, ChunkConfig config) {
        if (!strategies.contains(ChunkStrategyEnum.FIXED_SIZE)) {
            return List.of();
        }
        int chunkSize = config.getFixedChunkSize() != null ? config.getFixedChunkSize() : 500;
        int overlap = config.getSlideOverlap() != null ? config.getSlideOverlap() : 50;
        TextSplitter splitter = new RecursiveCharacterTextSplitter(chunkSize, overlap);
        return splitter.apply(documents);
    }
}
