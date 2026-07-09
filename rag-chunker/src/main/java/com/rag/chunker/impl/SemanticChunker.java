package com.rag.chunker.impl;

import com.rag.core.api.TextChunker;
import com.rag.core.config.ChunkConfig;
import com.rag.core.entity.Chunk;
import com.rag.core.entity.DocumentParseResult;
import com.rag.core.enums.ChunkStrategyEnum;
import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.*;

/**
 * 语义边界分片策略
 * 使用OpenNLP分句 + 语义相似度判定断点
 */
public class SemanticChunker implements TextChunker {

    private static final Logger log = LoggerFactory.getLogger(SemanticChunker.class);
    private SentenceDetectorME sentenceDetector;

    public SemanticChunker() {
        try {
            // 加载英文分句模型
            InputStream modelIn = getClass().getClassLoader()
                    .getResourceAsStream("opennlp/en-sent.bin");
            if (modelIn != null) {
                SentenceModel model = new SentenceModel(modelIn);
                this.sentenceDetector = new SentenceDetectorME(model);
            }
        } catch (Exception e) {
            log.warn("OpenNLP分句模型加载失败，将使用简单规则分句: {}", e.getMessage());
        }
    }

    @Override
    public List<Chunk> chunk(DocumentParseResult parseResult, ChunkConfig config) {
        String text = parseResult.getFullText();
        if (text == null || text.isEmpty()) return List.of();

        // 分句
        List<String> sentences = splitSentences(text);
        if (sentences.isEmpty()) return List.of();

        double threshold = config.getSemanticThreshold() != null ? config.getSemanticThreshold() : 0.7;
        int fixedChunkSize = config.getFixedChunkSize() != null ? config.getFixedChunkSize() : 500;

        List<Chunk> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();
        Set<String> currentWords = new HashSet<>();

        for (String sentence : sentences) {
            Set<String> sentenceWords = extractWords(sentence);

            double similarity = 0.0;
            if (!currentWords.isEmpty()) {
                similarity = jaccardSimilarity(currentWords, sentenceWords);
            }

            // 如果语义相似度低于阈值或当前chunk太长，则创建新chunk
            if (!currentChunk.isEmpty() &&
                (similarity < threshold || currentChunk.length() + sentence.length() > fixedChunkSize)) {
                String chunkText = currentChunk.toString().trim();
                if (!chunkText.isEmpty()) {
                    chunks.add(buildChunk(chunkText, parseResult));
                }
                currentChunk = new StringBuilder();
                currentWords.clear();
            }

            currentChunk.append(sentence).append(" ");
            currentWords.addAll(sentenceWords);
        }

        // 最后一个chunk
        String lastText = currentChunk.toString().trim();
        if (!lastText.isEmpty()) {
            chunks.add(buildChunk(lastText, parseResult));
        }

        log.debug("语义分片完成，生成{}个chunk", chunks.size());
        return chunks;
    }

    @Override
    public ChunkStrategyEnum getStrategy() {
        return ChunkStrategyEnum.SEMANTIC;
    }

    private List<String> splitSentences(String text) {
        if (sentenceDetector != null) {
            return Arrays.asList(sentenceDetector.sentDetect(text));
        }
        // fallback: 简单规则分句
        return Arrays.asList(text.split("(?<=[。！？.!?])\\s*"));
    }

    private Set<String> extractWords(String text) {
        Set<String> words = new HashSet<>();
        for (String word : text.toLowerCase().split("\\W+")) {
            if (!word.isEmpty()) words.add(word);
        }
        return words;
    }

    private double jaccardSimilarity(Set<String> set1, Set<String> set2) {
        if (set1.isEmpty() && set2.isEmpty()) return 1.0;
        Set<String> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);
        Set<String> union = new HashSet<>(set1);
        union.addAll(set2);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    private Chunk buildChunk(String text, DocumentParseResult parseResult) {
        return Chunk.builder()
                .chunkId(UUID.randomUUID().toString())
                .text(text)
                .chunkType(ChunkStrategyEnum.SEMANTIC)
                .textHash(hash(text))
                .fileName(parseResult.getMeta() != null ? parseResult.getMeta().getFileName() : null)
                .fileType(parseResult.getMeta() != null ? parseResult.getMeta().getFileType() : null)
                .fileId(parseResult.getMeta() != null ? parseResult.getMeta().getFileId() : null)
                .build();
    }

    private String hash(String text) {
        int h = 0;
        for (char c : text.toCharArray()) h = 31 * h + c;
        return Integer.toHexString(h);
    }
}
