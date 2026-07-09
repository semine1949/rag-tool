package com.rag.core.config;

import com.rag.core.enums.ChunkStrategyEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * 分片策略统一配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChunkConfig {
    /** 启用的分片策略集合 */
    private Set<ChunkStrategyEnum> enableStrategies;

    // === 1.固定切分参数 ===
    /** 固定chunk大小（字符数），默认500 */
    @Builder.Default
    private Integer fixedChunkSize = 500;
    /** 滑动重叠窗口大小（字符数），默认50 */
    @Builder.Default
    private Integer slideOverlap = 50;

    // === 2.语义切割参数 ===
    /** 语义断点相似度阈值（0-1），默认0.7 */
    @Builder.Default
    private Double semanticThreshold = 0.7;

    // === 3.表格切割 ===
    /** 表格是否独立分片 */
    @Builder.Default
    private Boolean splitTableSingleChunk = true;

    // === 4.代码切割 ===
    /** 是否按函数拆分代码块 */
    @Builder.Default
    private Boolean splitCodeByFunction = true;

    // === 5.标题层级切割 ===
    /** 最大标题层级深度 */
    @Builder.Default
    private Integer maxTitleLevel = 3;

    // === 6.父子分片 ===
    /** 父块字符长度 */
    @Builder.Default
    private Integer parentChunkLen = 1000;
    /** 子块字符长度 */
    @Builder.Default
    private Integer childChunkLen = 200;
}
