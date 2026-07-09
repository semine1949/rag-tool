package com.rag.chunker;

import com.rag.core.api.TextChunker;
import com.rag.core.config.ChunkConfig;
import com.rag.core.entity.Chunk;
import com.rag.core.entity.DocumentParseResult;
import com.rag.core.enums.ChunkStrategyEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 分片工厂 - 支持单策略/多策略组合切割
 * 策略执行顺序：先分离表格、代码块单独分片 → 正文按标题分层切割 → 语义/固定长度切割 → 父子关联分片
 */
public class ChunkerFactory implements TextChunker {

    private static final Logger log = LoggerFactory.getLogger(ChunkerFactory.class);

    private final Map<ChunkStrategyEnum, TextChunker> chunkerMap;

    public ChunkerFactory(Map<ChunkStrategyEnum, TextChunker> chunkerMap) {
        this.chunkerMap = chunkerMap;
    }

    @Override
    public List<Chunk> chunk(DocumentParseResult parseResult, ChunkConfig config) {
        Set<ChunkStrategyEnum> strategies = config.getEnableStrategies();
        if (strategies == null || strategies.isEmpty()) {
            log.warn("未配置分片策略，使用默认固定长度分片");
            strategies = Set.of(ChunkStrategyEnum.FIXED_SIZE);
        }

        List<Chunk> allChunks = new ArrayList<>();

        // 定义执行顺序
        List<ChunkStrategyEnum> executionOrder = buildExecutionOrder(strategies);

        // 第一阶段：先处理表格和代码块
        List<Chunk> tableChunks = executeStrategy(strategies, ChunkStrategyEnum.TABLE, parseResult, config);
        List<Chunk> codeChunks = executeStrategy(strategies, ChunkStrategyEnum.CODE_FUNCTION, parseResult, config);
        allChunks.addAll(tableChunks);
        allChunks.addAll(codeChunks);

        // 第二阶段：正文文本执行标题分层分片
        List<Chunk> titleChunks = executeStrategy(strategies, ChunkStrategyEnum.TITLE_HIERARCHY, parseResult, config);

        // 第三阶段：对标题分片结果或原始文本进行语义/固定长度分片
        List<Chunk> bodyChunks;
        if (!titleChunks.isEmpty()) {
            bodyChunks = titleChunks;
        } else {
            bodyChunks = executeStrategy(strategies, ChunkStrategyEnum.SEMANTIC, parseResult, config);
            if (bodyChunks.isEmpty()) {
                bodyChunks = executeStrategy(strategies, ChunkStrategyEnum.FIXED_SIZE, parseResult, config);
            }
        }
        allChunks.addAll(bodyChunks);

        // 第四阶段：父子分片
        List<Chunk> parentChildChunks = executeStrategy(strategies, ChunkStrategyEnum.PARENT_CHILD, parseResult, config);
        allChunks.addAll(parentChildChunks);

        // 生成chunkId
        for (int i = 0; i < allChunks.size(); i++) {
            Chunk c = allChunks.get(i);
            if (c.getChunkId() == null) {
                c.setChunkId(UUID.randomUUID().toString());
            }
        }

        log.info("分片完成，共生成 {} 个Chunk", allChunks.size());
        return allChunks;
    }

    @Override
    public ChunkStrategyEnum getStrategy() {
        return null; // 工厂本身不代表单一策略
    }

    private List<Chunk> executeStrategy(Set<ChunkStrategyEnum> strategies, ChunkStrategyEnum strategy,
                                        DocumentParseResult parseResult, ChunkConfig config) {
        if (strategies.contains(strategy)) {
            TextChunker chunker = chunkerMap.get(strategy);
            if (chunker != null) {
                try {
                    return chunker.chunk(parseResult, config);
                } catch (Exception e) {
                    log.error("分片策略 {} 执行失败: {}", strategy, e.getMessage(), e);
                }
            }
        }
        return List.of();
    }

    private List<ChunkStrategyEnum> buildExecutionOrder(Set<ChunkStrategyEnum> strategies) {
        List<ChunkStrategyEnum> order = new ArrayList<>();
        // 按处理顺序排列
        if (strategies.contains(ChunkStrategyEnum.TABLE)) order.add(ChunkStrategyEnum.TABLE);
        if (strategies.contains(ChunkStrategyEnum.CODE_FUNCTION)) order.add(ChunkStrategyEnum.CODE_FUNCTION);
        if (strategies.contains(ChunkStrategyEnum.TITLE_HIERARCHY)) order.add(ChunkStrategyEnum.TITLE_HIERARCHY);
        if (strategies.contains(ChunkStrategyEnum.SEMANTIC)) order.add(ChunkStrategyEnum.SEMANTIC);
        if (strategies.contains(ChunkStrategyEnum.FIXED_SIZE)) order.add(ChunkStrategyEnum.FIXED_SIZE);
        if (strategies.contains(ChunkStrategyEnum.PARENT_CHILD)) order.add(ChunkStrategyEnum.PARENT_CHILD);
        return order;
    }
}
