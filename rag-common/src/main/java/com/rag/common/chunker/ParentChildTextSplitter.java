package com.rag.common.chunker;

import com.rag.common.chunker.util.ChunkDocuments;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 父子两层分块策略（hierarchical_model）分片器。
 * <p>
 * 将工程文档（PDF/Word/合同/规章制度等）切分为「父子两层」结构：
 * <ol>
 *   <li><b>第 1 层（父块 parent）</b>：先用语义段落生成粒度适中的父块。</li>
 *   <li><b>第 2 层（子块 child）</b>：将每个父块按子块参数细分为子块，维护父子关联。</li>
 * </ol>
 * 子块是真正入向量库的检索粒度；父块用于检索时拼接完整上下文，
 * 实现「小块精准检索 + 父块补充完整上下文」的场景。
 * <p>
 * 核心参数（可按需覆盖，未指定时使用默认值）：
 * <ul>
 *   <li><b>父块配置 segmentation</b>：
 *     <ul>
 *       <li>{@code parentSeparator}：父块分隔符，默认 {@code "\n\n\n"}（多个换行，视为段落边界）。</li>
 *       <li>{@code parentMaxTokens}：父块最大长度，默认 {@code 2048}。</li>
 *     </ul>
 *   </li>
 *   <li><b>子块配置 subchunkSegmentation</b>：
 *     <ul>
 *       <li>{@code childSeparator}：子块分隔符，默认 {@code "\n\n"}。</li>
 *       <li>{@code childMaxTokens}：子块最大长度，默认 {@code 1024}。</li>
 *     </ul>
 *   </li>
 *   <li>{@code parentMode}：父块粒度模式，固定为 {@code "paragraph"}（段落模式）。</li>
 * </ul>
 * <p>
 * 输出元信息：父块 {@code chunkType="parent"}，含 chunkId/chunkIndex/chunkMode="hierarchical_model"/parentMode；
 * 子块 {@code chunkType="child"}，含 chunkId/parentChunkId/chunkIndex/chunkMode="hierarchical_model"。
 * 返回列表同时包含父块与子块，调用方可通过 {@code chunkType} 区分：
 * 子块用于向量化检索，父块用于上下文增强。
 */
public class ParentChildTextSplitter extends TextSplitter {

    /** 分块模式标识：层级父子分块策略 */
    public static final String CHUNK_MODE = "hierarchical_model";
    /** 父块粒度模式：段落模式 */
    public static final String PARENT_MODE_PARAGRAPH = "paragraph";

    /** 父块分隔符，默认多个换行，视为段落边界 */
    private String parentSeparator = "\n\n\n";
    /** 父块最大长度，默认 2048 */
    private int parentMaxTokens = 2048;
    /** 子块分隔符，默认双换行 */
    private String childSeparator = "\n\n";
    /** 子块最大长度，默认 1024 */
    private int childMaxTokens = 1024;
    /** 父块粒度模式，固定为段落模式 */
    private String parentMode = PARENT_MODE_PARAGRAPH;

    /** 句子边界字符集，用于超长段落/片段的二次截断 */
    private static final char[] SENTENCE_BOUNDARIES = {'。', '！', '？', '；', '.', '!', '?', ';', '\n'};

    public ParentChildTextSplitter() {
    }

    /**
     * 全参构造，可按需覆盖默认参数。
     *
     * @param parentSeparator 父块分隔符
     * @param parentMaxTokens 父块最大长度
     * @param childSeparator  子块分隔符
     * @param childMaxTokens  子块最大长度
     * @param parentMode      父块粒度模式（固定 paragraph）
     */
    public ParentChildTextSplitter(String parentSeparator, int parentMaxTokens,
                                   String childSeparator, int childMaxTokens, String parentMode) {
        this.parentSeparator = parentSeparator;
        this.parentMaxTokens = parentMaxTokens;
        this.childSeparator = childSeparator;
        this.childMaxTokens = childMaxTokens;
        this.parentMode = parentMode;
    }

    // ===== 参数 getter（v3：供参数快照提取） =====

    /** 父块分隔符 */
    public String getParentSeparator() { return parentSeparator; }

    /** 父块最大长度 */
    public int getParentMaxTokens() { return parentMaxTokens; }

    /** 子块分隔符 */
    public String getChildSeparator() { return childSeparator; }

    /** 子块最大长度 */
    public int getChildMaxTokens() { return childMaxTokens; }

    /** 父块粒度模式 */
    public String getParentMode() { return parentMode; }

    /**
     * 满足 {@link TextSplitter} 抽象方法要求：原样返回。
     * <p>主逻辑在 {@link #apply(List)} 中完成，此方法不会被框架默认调用路径走到。</p>
     */
    @Override
    protected List<String> splitText(String text) {
        return List.of(text);
    }

    /**
     * 切分主逻辑：先生成父块，再将每个父块细分为子块。
     *
     * @param documents 输入 Document 列表（通常只有一个承载全文的 Document）
     * @return 父块 + 子块的 Document 列表，通过 chunkType 区分 parent / child
     */
    @Override
    public List<Document> apply(List<Document> documents) {
        String rawText = ChunkDocuments.fullText(documents);
        if (rawText == null || rawText.isEmpty()) {
            return List.of();
        }
        Document input = documents.get(0);

        // ========== 第 1 层：生成父块 ==========
        // 阶段1：文本清洗
        String cleaned = cleanText(rawText);
        if (cleaned.isEmpty()) {
            return List.of();
        }
        // 阶段2：语义段落分割
        List<String> paragraphs = splitByParagraph(cleaned);
        // 阶段3：短段落合并
        List<String> merged = mergeShortParagraphs(paragraphs);
        // 阶段4：生成父块 + 超长段落拆分
        List<ParentBlock> parentBlocks = buildParents(merged, input);

        // ========== 第 2 层：将每个父块细分为子块 ==========
        List<Document> result = new ArrayList<>();
        for (ParentBlock parent : parentBlocks) {
            result.add(parent.document);
            for (Document child : splitParentToSubChunks(parent, input)) {
                result.add(child);
            }
        }

        return result;
    }

    // ==================== 父块生成 ====================

    /**
     * 阶段1：文本清洗。
     * <ul>
     *   <li>统一换行符：\r\n、\r → \n。</li>
     *   <li>压缩连续空行：3 个及以上 \n → \n\n（最多保留一个空行作为段落边界）。</li>
     *   <li>去除首尾空白。</li>
     * </ul>
     */
    private String cleanText(String text) {
        String t = text.replace("\r\n", "\n").replace("\r", "\n");
        // 3 个及以上 \n 压缩为 \n\n（正则：至少 3 个换行，保留 2 个）
        t = t.replaceAll("\n{3,}", "\n\n");
        return t.trim();
    }

    /**
     * 阶段2：语义段落分割。
     * <ul>
     *   <li>先按双换行 \n\n 切分为大段落。</li>
     *   <li>段落内若含单换行，逐行检测：
     *     空行 → 结束当前段落；命中标题行（编号开头且长度 ≤ 50 字符）且当前已累积内容 → 另起新段落；否则累加到当前段落。</li>
     *   <li>过滤全空白片段。</li>
     * </ul>
     */
    private List<String> splitByParagraph(String text) {
        List<String> paragraphs = new ArrayList<>();
        String[] bigParts = text.split("\n\n");
        for (String bigPart : bigParts) {
            if (!bigPart.contains("\n")) {
                if (!isBlank(bigPart)) {
                    paragraphs.add(bigPart.trim());
                }
                continue;
            }
            // 含单换行的段落：逐行检测标题行
            StringBuilder current = new StringBuilder();
            for (String line : bigPart.split("\n")) {
                if (isBlank(line)) {
                    // 空行 → 结束当前段落
                    if (current.length() > 0) {
                        paragraphs.add(current.toString().trim());
                        current.setLength(0);
                    }
                    continue;
                }
                String trimmedLine = line.trim();
                if (current.length() > 0 && isTitleLine(trimmedLine)) {
                    // 命中标题行且已有累积内容 → 另起新段落
                    paragraphs.add(current.toString().trim());
                    current.setLength(0);
                    current.append(trimmedLine);
                } else {
                    if (current.length() > 0) {
                        current.append('\n');
                    }
                    current.append(trimmedLine);
                }
            }
            if (current.length() > 0) {
                paragraphs.add(current.toString().trim());
            }
        }
        return paragraphs;
    }

    /**
     * 判断是否为标题行：以编号开头（第X章/第X条/一、/（一）/1. 等）且长度 ≤ 50 字符。
     */
    private boolean isTitleLine(String line) {
        if (line == null || line.length() > 50) {
            return false;
        }
        // 匹配：第X章/第X条/第X节/第X部分、一、/二、等中文序号、 (一)/（一） 、1. / 1、 等编号开头
        String regex = "^(第[一二三四五六七八九十百千万0-9]+[章条节部分篇]|[一二三四五六七八九十]+、|（[一二三四五六七八九十]+）|\\([一二三四五六七八九十]+\\)|[0-9]+[.、]|[一二三四五六七八九十]+[.])";
        return line.matches(regex + ".*");
    }

    /**
     * 阶段3：短段落合并。
     * 将相邻短段落贪婪合并，累加后长度不超过父块 maxTokens，恢复被解析拆散的语义（如条款标题+正文）。
     */
    private List<String> mergeShortParagraphs(List<String> paragraphs) {
        List<String> merged = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String p : paragraphs) {
            if (p.length() > parentMaxTokens) {
                // 超长段落单独处理，先落盘当前累积
                if (current.length() > 0) {
                    merged.add(current.toString());
                    current.setLength(0);
                }
                merged.add(p);
                continue;
            }
            // 尝试合并：当前 + 分隔符 + 段落 ≤ 父块 maxTokens
            int separatorLen = current.length() > 0 ? parentSeparator.length() : 0;
            if (current.length() + separatorLen + p.length() <= parentMaxTokens) {
                if (current.length() > 0) {
                    current.append(parentSeparator);
                }
                current.append(p);
            } else {
                if (current.length() > 0) {
                    merged.add(current.toString());
                }
                current.setLength(0);
                current.append(p);
            }
        }
        if (current.length() > 0) {
            merged.add(current.toString());
        }
        return merged;
    }

    /**
     * 阶段4：生成父块 + 超长段落拆分。
     * 按顺序把段落拼入当前父块，达到 parentMaxTokens 则保存父块并换新；
     * 单段落超长时，在句子边界处截断为多个独立父块。
     *
     * @return 父块列表（含完整内容文本）
     */
    private List<ParentBlock> buildParents(List<String> paragraphs, Document input) {
        List<ParentBlock> parents = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int chunkIndex = 0;

        for (String paragraph : paragraphs) {
            if (paragraph.length() > parentMaxTokens) {
                // 超长段落：先保存当前累积的父块
                if (current.length() > 0) {
                    parents.add(createParent(input, current.toString(), chunkIndex++));
                    current.setLength(0);
                }
                // 在句子边界截断为多个父块
                for (String seg : splitOversizedText(paragraph, parentMaxTokens)) {
                    parents.add(createParent(input, seg, chunkIndex++));
                }
                continue;
            }
            int separatorLen = current.length() > 0 ? parentSeparator.length() : 0;
            if (current.length() + separatorLen + paragraph.length() <= parentMaxTokens) {
                if (current.length() > 0) {
                    current.append(parentSeparator);
                }
                current.append(paragraph);
            } else {
                if (current.length() > 0) {
                    parents.add(createParent(input, current.toString(), chunkIndex++));
                }
                current.setLength(0);
                current.append(paragraph);
            }
        }
        if (current.length() > 0) {
            parents.add(createParent(input, current.toString(), chunkIndex));
        }
        return parents;
    }

    /**
     * 构造父块对象，写入 chunkType=parent / chunkId / chunkIndex / chunkMode / parentMode 元信息。
     */
    private ParentBlock createParent(Document input, String text, int chunkIndex) {
        String chunkId = UUID.randomUUID().toString();
        Map<String, Object> extra = new HashMap<>();
        extra.put("chunkId", chunkId);
        extra.put("chunkIndex", chunkIndex);
        extra.put("chunkMode", CHUNK_MODE);
        extra.put("parentMode", parentMode);
        Document doc = ChunkDocuments.of(input, text, "parent", extra);
        return new ParentBlock(chunkId, doc);
    }

    // ==================== 子块生成 ====================

    /**
     * 第 2 层：将每个父块按子块分隔符切分为片段，累加不超过子块 maxTokens。
     * 单个片段超过子块 maxTokens 时，在句子边界处二次截断。
     * 每个子块记录 parentChunkId 关联其父块。
     *
     * @param parent 父块
     * @param input  输入 Document，用于继承业务元数据
     * @return 子块 Document 列表
     */
    private List<Document> splitParentToSubChunks(ParentBlock parent, Document input) {
        List<Document> children = new ArrayList<>();
        String text = parent.document.getText();
        if (text == null || text.isEmpty()) {
            return children;
        }
        String[] parts = text.split(java.util.regex.Pattern.quote(childSeparator), -1);
        StringBuilder current = new StringBuilder();
        int chunkIndex = 0;

        for (String part : parts) {
            if (isBlank(part)) {
                continue;
            }
            int separatorLen = current.length() > 0 ? childSeparator.length() : 0;
            if (current.length() + separatorLen + part.length() <= childMaxTokens) {
                if (current.length() > 0) {
                    current.append(childSeparator);
                }
                current.append(part);
                continue;
            }
            // 保存当前子块
            if (current.length() > 0) {
                children.add(createChild(input, parent.chunkId, current.toString(), chunkIndex++));
                current.setLength(0);
            }
            // 片段自身超长 → 在句子边界二次截断
            if (part.length() > childMaxTokens) {
                for (String seg : splitOversizedText(part, childMaxTokens)) {
                    children.add(createChild(input, parent.chunkId, seg, chunkIndex++));
                }
            } else {
                current.append(part);
            }
        }
        if (current.length() > 0) {
            children.add(createChild(input, parent.chunkId, current.toString(), chunkIndex));
        }
        return children;
    }

    /**
     * 构造子块对象，写入 chunkType=child / chunkId / parentChunkId / chunkIndex / chunkMode 元信息。
     */
    private Document createChild(Document input, String parentChunkId, String text, int chunkIndex) {
        Map<String, Object> extra = new HashMap<>();
        extra.put("chunkId", UUID.randomUUID().toString());
        extra.put("parentChunkId", parentChunkId);
        extra.put("chunkIndex", chunkIndex);
        extra.put("chunkMode", CHUNK_MODE);
        return ChunkDocuments.of(input, text, "child", extra);
    }

    // ==================== 通用工具 ====================

    /**
     * 超长文本在句子边界处截断为多个块，每块不超过 maxLen。
     * 优先在最近的句子边界（。.! ！? ？；\n 等）处断开，避免破坏句子语义。
     */
    private List<String> splitOversizedText(String text, int maxLen) {
        List<String> result = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxLen, text.length());
            if (end < text.length()) {
                int bp = lastSentenceBoundary(text, start, end);
                if (bp > start) {
                    end = bp;
                }
            }
            String seg = text.substring(start, end).trim();
            if (!seg.isEmpty()) {
                result.add(seg);
            }
            // 防止无限循环：若未前进则强制前进
            if (end <= start) {
                end = Math.min(start + maxLen, text.length());
            }
            start = end;
        }
        return result;
    }

    /**
     * 在 [start, end] 区间内查找最近的句子边界位置（返回边界字符之后的位置）。
     * 若未找到任何边界，返回 -1。
     */
    private int lastSentenceBoundary(String text, int start, int end) {
        int found = -1;
        for (int i = end; i > start; i--) {
            char c = text.charAt(i - 1);
            for (char b : SENTENCE_BOUNDARIES) {
                if (c == b) {
                    found = i;
                    break;
                }
            }
            if (found > 0) {
                break;
            }
        }
        return found;
    }

    /**
     * 判断字符串是否为空白。
     */
    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * 内部容器：携带父块 chunkId 与其对应 Document。
     */
    private static final class ParentBlock {
        final String chunkId;
        final Document document;

        ParentBlock(String chunkId, Document document) {
            this.chunkId = chunkId;
            this.document = document;
        }
    }
}
