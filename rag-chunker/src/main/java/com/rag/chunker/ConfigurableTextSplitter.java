package com.rag.chunker;

import com.rag.core.config.ChunkConfig;
import org.springframework.ai.transformer.splitter.TextSplitter;

import java.util.List;

/**
 * 可注入 {@link ChunkConfig} 的文本分片器。
 * <p>
 * 框架 {@link TextSplitter#apply(List)} 不携带配置，故扩展一个 setConfig 供
 * {@link ChunkerFactory} 在每次分片前注入知识库级配置。
 */
public abstract class ConfigurableTextSplitter extends TextSplitter {

    private ChunkConfig config;

    public void setConfig(ChunkConfig config) {
        this.config = config;
    }

    protected ChunkConfig getConfig() {
        return config;
    }

    /**
     * 默认实现：原样返回。具体分片逻辑在 {@link #apply(List)} 中完成，
     * 框架 {@code split(Document)} 默认实现不会走到此处。
     */
    @Override
    protected List<String> splitText(String text) {
        return List.of(text);
    }
}
