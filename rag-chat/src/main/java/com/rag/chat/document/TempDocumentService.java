package com.rag.chat.document;

import com.rag.common.parser.DocumentParseFactory;
import com.rag.chat.config.ChatProperties;
import com.rag.config.factory.AiModelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 临时文档服务。
 * <p>
 * 处理用户在问答过程中临时上传的文档：解析 → 分块 → 向量化 → 内存召回。
 * 遵循以下约定：
 * <ul>
 *   <li>临时文档数据仅用于当前会话问答，不持久化至正式知识库与向量数据库</li>
 *   <li>会话结束后自动清理（基于会话 ID 维护临时向量池）</li>
 *   <li>临时文档向量化需与目标知识库使用相同模型，确保向量空间可比</li>
 *   <li>单次问答请求临时文档数量受 {@link ChatProperties#getTempDocMaxCount()} 限制</li>
 * </ul>
 * </p>
 *
 * @author rag-tool
 * @since 1.0
 */
@Component
public class TempDocumentService {

    private static final Logger log = LoggerFactory.getLogger(TempDocumentService.class);

    /** 文档解析工厂 */
    private final DocumentParseFactory documentParseFactory;

    /** AI 模型工厂 */
    private final AiModelFactory aiModelFactory;

    /** 问答全局配置 */
    private final ChatProperties chatProperties;

    /** 会话级临时向量池：sessionId → 临时文档片段列表 */
    private final ConcurrentHashMap<String, List<Document>> tempDocPool = new ConcurrentHashMap<>();

    /**
     * 构造函数注入。
     *
     * @param documentParseFactory 文档解析工厂
     * @param aiModelFactory       AI 模型工厂
     * @param chatProperties       问答全局配置
     */
    public TempDocumentService(DocumentParseFactory documentParseFactory,
                               AiModelFactory aiModelFactory,
                               ChatProperties chatProperties) {
        this.documentParseFactory = documentParseFactory;
        this.aiModelFactory = aiModelFactory;
        this.chatProperties = chatProperties;
    }

    /**
     * 处理临时上传文档：解析 → 分块 → 向量化 → 存入会话临时池。
     *
     * @param sessionId     会话 ID
     * @param file          上传文件
     * @param embeddingModelName 向量化模型名（须与目标知识库一致）
     * @return 处理后的文档分块数量
     */
    public int processTempDocument(String sessionId, File file, String embeddingModelName) {
        List<Document> existing = tempDocPool.computeIfAbsent(sessionId, k -> new ArrayList<>());
        // 数量上限校验
        if (existing.size() >= chatProperties.getTempDocMaxCount()) {
            throw new IllegalStateException(
                    "临时文档数量已达上限 " + chatProperties.getTempDocMaxCount());
        }

        try {
            // 1. 解析文档
            List<Document> parsed = documentParseFactory.parse(file);
            if (parsed == null || parsed.isEmpty()) {
                return 0;
            }

            // 2. 分块（复用 SizeTextSplitter 默认参数，临时文档不定制分块策略）
            // 此处简化处理：直接使用解析结果作为分块（解析器已按格式返回合理粒度）
            List<Document> chunks = new ArrayList<>();
            for (Document doc : parsed) {
                // 为每个分块生成唯一 ID
                doc.getMetadata().put("chunkId", UUID.randomUUID().toString());
                doc.getMetadata().put("tempDoc", true);
                chunks.add(doc);
            }

            // 3. 向量化（与目标知识库使用相同模型，确保向量空间可比）
            EmbeddingModel embeddingModel = aiModelFactory.getEmbeddingModel(embeddingModelName);
            // Spring AI EmbeddingModel 会在 add 时自动向量化（此处仅预生成向量以提前发现问题）
            for (Document chunk : chunks) {
                embeddingModel.embed(chunk.getText());
            }

            // 4. 存入会话临时池
            existing.addAll(chunks);
            log.info("临时文档处理完成 sessionId={} chunks={} total={}",
                    sessionId, chunks.size(), existing.size());
            return chunks.size();
        } catch (Exception e) {
            log.error("临时文档处理失败 sessionId={} file={}", sessionId, file.getName(), e);
            throw new RuntimeException("临时文档处理失败: " + e.getMessage(), e);
        }
    }

    /**
     * 基于临时文档进行内存召回（向量相似度匹配）。
     *
     * @param sessionId          会话 ID
     * @param query              用户查询
     * @param embeddingModelName 向量化模型名
     * @param topK               召回数量
     * @return 召回的文档片段列表（按相似度倒序）
     */
    public List<Document> recallFromTemp(String sessionId, String query,
                                         String embeddingModelName, int topK) {
        List<Document> tempDocs = tempDocPool.get(sessionId);
        if (tempDocs == null || tempDocs.isEmpty()) {
            return new ArrayList<>();
        }

        try {
            EmbeddingModel embeddingModel = aiModelFactory.getEmbeddingModel(embeddingModelName);
            float[] queryVec = embeddingModel.embed(query);

            // 计算相似度并排序
            List<ScoredDocument> scored = new ArrayList<>(tempDocs.size());
            for (Document doc : tempDocs) {
                float[] docVec = embeddingModel.embed(doc.getText());
                double sim = cosineSimilarity(queryVec, docVec);
                scored.add(new ScoredDocument(doc, sim));
            }
            scored.sort((a, b) -> Double.compare(b.score, a.score));

            // 截取 topK
            List<Document> result = new ArrayList<>(Math.min(topK, scored.size()));
            for (int i = 0; i < Math.min(topK, scored.size()); i++) {
                Document doc = scored.get(i).doc;
                doc.getMetadata().put("score", scored.get(i).score);
                result.add(doc);
            }
            return result;
        } catch (Exception e) {
            log.error("临时文档召回失败 sessionId={}", sessionId, e);
            return new ArrayList<>();
        }
    }

    /**
     * 清理会话的临时文档池。
     *
     * @param sessionId 会话 ID
     */
    public void cleanupSession(String sessionId) {
        tempDocPool.remove(sessionId);
        log.info("临时文档池已清理 sessionId={}", sessionId);
    }

    /**
     * 计算两个向量的余弦相似度。
     *
     * @param vecA 向量 A
     * @param vecB 向量 B
     * @return 相似度（-1.0~1.0）
     */
    private double cosineSimilarity(float[] vecA, float[] vecB) {
        if (vecA.length != vecB.length) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < vecA.length; i++) {
            dot += vecA[i] * vecB[i];
            normA += vecA[i] * vecA[i];
            normB += vecB[i] * vecB[i];
        }
        if (normA == 0 || normB == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * 带得分的文档（用于内部排序）。
     */
    private static class ScoredDocument {
        final Document doc;
        final double score;

        ScoredDocument(Document doc, double score) {
            this.doc = doc;
            this.score = score;
        }
    }
}