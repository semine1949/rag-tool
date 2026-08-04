package com.rag.chunker;

import com.rag.core.enums.ChunkStrategyEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.transformer.splitter.TextSplitter;

/**
 * 分片策略工厂（封装式）：依据分片策略与参数配置选择并构造对应的 splitter。
 * <p>
 * 外部调用方只传入 {@code chunkStrategy} 与 {@link SplitterConfig}，由工厂内部完成
 * 策略判定与 splitter 构造，无需在外部做 if/else 判断。
 * <ul>
 *   <li>{@code text-model}（TEXT_MODEL）→ {@link SizeTextSplitter}</li>
 *   <li>{@code hierarchical-model}（HIERARCHICAL_MODEL）→ {@link ParentChildTextSplitter}</li>
 * </ul>
 * 策略为 null 或未知时回退 {@code text-model}（TEXT_MODEL）。
 */
public class ChunkStrategyFactory {

    private static final Logger log = LoggerFactory.getLogger(ChunkStrategyFactory.class);

    /**
     * 依据分片策略与配置选择并构造 splitter。
     * <p>config 中为 null 的字段由具体 splitter 采用默认值；config 整体为 null 时同样采用默认参数。</p>
     *
     * @param chunkStrategy 分片策略名（如 TEXT_MODEL / text-model，可空；未知回退 text-model）
     * @param config        分片参数载体（可空，null 时采用默认参数）
     * @return 对应的 {@link TextSplitter} 实例
     */
    public TextSplitter getSplitter(String chunkStrategy, SplitterConfig config) {
        ChunkStrategyEnum strategy = parse(chunkStrategy);
        switch (strategy) {
            case HIERARCHICAL_MODEL:
                return buildHierarchical(config);
            case TEXT_MODEL:
            default:
                return buildTextModel(config);
        }
    }

    /**
     * 解析分片策略名；null/空白/未知均回退 TEXT_MODEL。
     */
    private ChunkStrategyEnum parse(String chunkStrategy) {
        if (chunkStrategy == null || chunkStrategy.isBlank()) {
            return ChunkStrategyEnum.TEXT_MODEL;
        }
        try {
            return ChunkStrategyEnum.valueOf(chunkStrategy.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("未知分片策略 '{}'，回退默认 text-model", chunkStrategy);
            return ChunkStrategyEnum.TEXT_MODEL;
        }
    }

    /**
     * 构造通用文本分块 splitter（text-model）。
     * config 中为 null 的字段由 {@link SizeTextSplitter} 采用默认值。
     */
    private SizeTextSplitter buildTextModel(SplitterConfig config) {
        if (config == null) {
            return new SizeTextSplitter();
        }
        String delimiter = config.getDelimiter() != null ? config.getDelimiter() : "\n";
        int maxTokens = config.getMaxTokens() != null ? config.getMaxTokens() : 1024;
        int chunkOverlap = config.getChunkOverlap() != null ? config.getChunkOverlap() : 50;
        return new SizeTextSplitter(delimiter, maxTokens, chunkOverlap);
    }

    /**
     * 构造层级父子分块 splitter（hierarchical-model）。
     * config 中为 null 的字段由 {@link ParentChildTextSplitter} 采用默认值。
     */
    private ParentChildTextSplitter buildHierarchical(SplitterConfig config) {
        if (config == null) {
            return new ParentChildTextSplitter();
        }
        String parentSeparator = config.getParentSeparator() != null ? config.getParentSeparator() : "\n\n\n";
        int parentMaxTokens = config.getParentMaxTokens() != null ? config.getParentMaxTokens() : 2048;
        String childSeparator = config.getChildSeparator() != null ? config.getChildSeparator() : "\n\n";
        int childMaxTokens = config.getChildMaxTokens() != null ? config.getChildMaxTokens() : 1024;
        String parentMode = config.getParentMode() != null ? config.getParentMode() : "paragraph";
        return new ParentChildTextSplitter(parentSeparator, parentMaxTokens,
                childSeparator, childMaxTokens, parentMode);
    }
}
