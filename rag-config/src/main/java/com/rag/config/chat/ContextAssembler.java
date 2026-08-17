package com.rag.config.chat;

import com.rag.common.chat.Citation;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文组装器。
 * <p>
 * 整合多来源召回的知识片段为模型可用的上下文，遵循以下约定：
 * <ul>
 *   <li>按召回顺序编号 [1]..[n]，与引用溯源编号一一对应</li>
 *   <li>Token 预算耗尽时按相似度优先级截断</li>
 *   <li>轻量 Token 估算采用中文 1 字符/token、英文 4 字符/token 近似</li>
 *   <li>引用关系严格对应原始召回片段，无来源的生成内容不得标注引用</li>
 * </ul>
 * </p>
 *
 * @author rag-tool
 * @since 1.0
 */
@Component
public class ContextAssembler {

    /** 单条片段最大展示长度（避免过长片段撑爆上下文） */
    private static final int MAX_SNIPPET_LENGTH = 800;

    /**
     * 组装召回片段为模型上下文。
     *
     * @param documents           召回的文档片段列表（已按相似度倒序排列）
     * @param contextWindowTokens 上下文窗口 Token 预算
     * @return 组装结果（含上下文文本与引用列表）
     */
    public AssembledContext assemble(List<Document> documents, int contextWindowTokens) {
        if (documents == null || documents.isEmpty()) {
            return new AssembledContext("", new ArrayList<>());
        }

        StringBuilder contextBuilder = new StringBuilder();
        List<Citation> citations = new ArrayList<>();
        int usedTokens = 0;

        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            String snippet = doc.getText();
            // 截断过长片段
            if (snippet.length() > MAX_SNIPPET_LENGTH) {
                snippet = snippet.substring(0, MAX_SNIPPET_LENGTH) + "...";
            }

            int snippetTokens = estimateTokens(snippet);
            // Token 预算耗尽，停止追加（保留 100 token 余量给编号与换行符）
            if (usedTokens + snippetTokens > contextWindowTokens - 100) {
                break;
            }

            int citationIndex = i + 1;
            // 按召回顺序编号 [1]..[n]
            contextBuilder.append("[").append(citationIndex).append("] ")
                    .append(snippet).append("\n\n");

            // 构建引用元数据
            Citation citation = buildCitation(doc, citationIndex, snippet);
            citations.add(citation);

            usedTokens += snippetTokens;
        }

        return new AssembledContext(contextBuilder.toString(), citations);
    }

    /**
     * 轻量 Token 估算：中文 1 字符/token，其他（英文/符号）4 字符/token。
     * <p>避免外部分词依赖，近似估算用于上下文裁剪决策。</p>
     *
     * @param text 待估算文本
     * @return 估算的 Token 数
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjkCount = 0;
        int otherCount = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // CJK 统一汉字范围（简化判定，覆盖常见中文）
            if (c >= '\u4E00' && c <= '\u9FFF') {
                cjkCount++;
            } else {
                otherCount++;
            }
        }
        return cjkCount + otherCount / 4;
    }

    /**
     * 从召回文档构建引用元数据。
     *
     * @param doc           召回文档
     * @param citationIndex 引用编号
     * @param snippet       截断后的片段
     * @return 引用元数据
     */
    private Citation buildCitation(Document doc, int citationIndex, String snippet) {
        return Citation.builder()
                .index(citationIndex)
                .docId(getLongMeta(doc, "docId"))
                .kbId(getLongMeta(doc, "kbId"))
                .fileName(getStringMeta(doc, "fileName"))
                .chunkId(getStringMeta(doc, "chunkId"))
                .snippet(snippet)
                .score(getDoubleMeta(doc, "score"))
                .build();
    }

    /**
     * 安全获取元数据字符串值。
     */
    private String getStringMeta(Document doc, String key) {
        Object val = doc.getMetadata().get(key);
        return val == null ? null : val.toString();
    }

    /**
     * 安全获取元数据 Long 值。
     */
    private Long getLongMeta(Document doc, String key) {
        Object val = doc.getMetadata().get(key);
        if (val == null) {
            return null;
        }
        if (val instanceof Number) {
            return ((Number) val).longValue();
        }
        try {
            return Long.parseLong(val.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 安全获取元数据 Double 值。
     */
    private Double getDoubleMeta(Document doc, String key) {
        Object val = doc.getMetadata().get(key);
        if (val == null) {
            return null;
        }
        if (val instanceof Number) {
            return ((Number) val).doubleValue();
        }
        try {
            return Double.parseDouble(val.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 上下文组装结果。
     */
    public static class AssembledContext {
        /** 组装后的上下文文本（含编号 [1]..[n]） */
        private final String contextText;
        /** 引用元数据列表（与编号一一对应） */
        private final List<Citation> citations;

        public AssembledContext(String contextText, List<Citation> citations) {
            this.contextText = contextText;
            this.citations = citations;
        }

        public String getContextText() {
            return contextText;
        }

        public List<Citation> getCitations() {
            return citations;
        }
    }
}
