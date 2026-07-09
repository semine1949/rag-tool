package com.rag.core.api;

import com.rag.core.config.ChunkConfig;
import com.rag.core.entity.Chunk;
import com.rag.core.entity.DocumentParseResult;

import java.util.List;

/**
 * 文本分片器顶层接口
 */
public interface TextChunker {

    /**
     * 根据解析结果和分片配置，生成Chunk列表
     * @param parseResult 文档解析结果
     * @param config 分片配置
     * @return 分片列表
     */
    List<Chunk> chunk(DocumentParseResult parseResult, ChunkConfig config);

    /**
     * 返回当前实现的分片策略类型
     * @return 策略类型
     */
    com.rag.core.enums.ChunkStrategyEnum getStrategy();
}
