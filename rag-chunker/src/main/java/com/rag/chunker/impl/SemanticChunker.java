package com.rag.chunker.impl;

import com.rag.chunker.ConfigurableTextSplitter;
import com.rag.chunker.util.ChunkDocuments;
import com.rag.core.config.ChunkConfig;
import com.rag.core.enums.ChunkStrategyEnum;
import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

import java.io.InputStream;
import java.util.*;

/**
 * 语义边界分片策略：OpenNLP 分句 + Jaccard 相似度判定断点。
 * 实现 Spring AI {@link ConfigurableTextSplitter}，产出 {@link Document} 列表。
 */
public class SemanticChunker extends ConfigurableTextSplitter {

    private static final Logger log = LoggerFactory.getLogger(SemanticChunker.class);
    private SentenceDetectorME sentenceDetector;
    private ChunkConfig config;

    public SemanticChunker() {
        try {
            InputStream modelIn = getClass().getClassLoader().getResourceAsStream("opennlp/en-sent.bin");
            if (modelIn != null) {
                SentenceModel model = new SentenceModel(modelIn);
                this.sentenceDetector = new SentenceDetectorME(model);
            }
        } catch (Exception e) {
            log.warn("OpenNLP 分句模型加载失败，将使用简单规则分句: {}", e.getMessage());
        }
    }

    @Override
    public void setConfig(ChunkConfig config) {
        this.config = config;
    }

    @Override
    public List<Document> apply(List<Document> documents) {
        String text = ChunkDocuments.fullText(documents);
        if (text.isEmpty()) {
            return List.of();
        }
        List<String> sentences = splitSentences(text);
        if (sentences.isEmpty()) {
            return List.of();
        }

        double threshold = config != null && config.getSemanticThreshold() != null
                ? config.getSemanticThreshold() : 0.7;
        int fixedChunkSize = config != null && config.getFixedChunkSize() != null
                ? config.getFixedChunkSize() : 500;

        List<Document> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();
        Set<String> currentWords = new HashSet<>();
        Document input = documents.get(0);

        for (String sentence : sentences) {
            Set<String> sentenceWords = extractWords(sentence);
            double similarity = currentWords.isEmpty() ? 0.0 : jaccardSimilarity(currentWords, sentenceWords);

            if (!currentChunk.isEmpty()
                    && (similarity < threshold || currentChunk.length() + sentence.length() > fixedChunkSize)) {
                String chunkText = currentChunk.toString().trim();
                if (!chunkText.isEmpty()) {
                    chunks.add(ChunkDocuments.of(input, chunkText, ChunkStrategyEnum.SEMANTIC.name(), null));
                }
                currentChunk.setLength(0);
                currentWords.clear();
            }
            currentChunk.append(sentence).append(" ");
            currentWords.addAll(sentenceWords);
        }

        String lastText = currentChunk.toString().trim();
        if (!lastText.isEmpty()) {
            chunks.add(ChunkDocuments.of(input, lastText, ChunkStrategyEnum.SEMANTIC.name(), null));
        }

        log.debug("语义分片完成，生成{}个chunk", chunks.size());
        return chunks;
    }

    private List<String> splitSentences(String text) {
        if (sentenceDetector != null) {
            return Arrays.asList(sentenceDetector.sentDetect(text));
        }
        return Arrays.asList(text.split("(?<=[。！？.!?])\\s*"));
    }

    private Set<String> extractWords(String text) {
        Set<String> words = new HashSet<>();
        for (String word : text.toLowerCase().split("\\W+")) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        return words;
    }

    private double jaccardSimilarity(Set<String> set1, Set<String> set2) {
        if (set1.isEmpty() && set2.isEmpty()) {
            return 1.0;
        }
        Set<String> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);
        Set<String> union = new HashSet<>(set1);
        union.addAll(set2);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }
}
