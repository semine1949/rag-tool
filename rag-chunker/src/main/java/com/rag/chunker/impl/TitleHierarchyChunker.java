package com.rag.chunker.impl;

import com.rag.core.api.TextChunker;
import com.rag.core.config.ChunkConfig;
import com.rag.core.entity.*;
import com.rag.core.enums.ChunkStrategyEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 标题层级分片策略
 * 按Markdown/HTML标题层级递归切割，父子标题绑定元数据
 */
public class TitleHierarchyChunker implements TextChunker {

    private static final Logger log = LoggerFactory.getLogger(TitleHierarchyChunker.class);

    // 匹配 Markdown 标题：行首 # 开头
    private static final Pattern MD_HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);

    @Override
    public List<Chunk> chunk(DocumentParseResult parseResult, ChunkConfig config) {
        String text = parseResult.getFullText();
        if (text == null || text.isEmpty()) return List.of();

        int maxLevel = config.getMaxTitleLevel() != null ? config.getMaxTitleLevel() : 3;

        List<TitleSection> sections = extractSections(text, maxLevel);
        if (sections.size() <= 1) return List.of(); // 标题太少，不做标题分层分片

        List<Chunk> chunks = new ArrayList<>();
        for (TitleSection section : sections) {
            if (section.content == null || section.content.trim().isEmpty()) continue;

            Map<String, Object> titleMeta = new HashMap<>();
            titleMeta.put("level", section.level);
            titleMeta.put("title", section.title);
            titleMeta.put("parentTitle", section.parentTitle);

            chunks.add(Chunk.builder()
                    .chunkId(UUID.randomUUID().toString())
                    .text(section.title + "\n" + section.content.trim())
                    .parentId(section.parentId)
                    .chunkType(ChunkStrategyEnum.TITLE_HIERARCHY)
                    .textHash(hash(section.content))
                    .fileName(parseResult.getMeta() != null ? parseResult.getMeta().getFileName() : null)
                    .fileType(parseResult.getMeta() != null ? parseResult.getMeta().getFileType() : null)
                    .fileId(parseResult.getMeta() != null ? parseResult.getMeta().getFileId() : null)
                    .titleMeta(titleMeta)
                    .build());
        }

        log.debug("标题层级分片完成，生成{}个chunk", chunks.size());
        return chunks;
    }

    @Override
    public ChunkStrategyEnum getStrategy() {
        return ChunkStrategyEnum.TITLE_HIERARCHY;
    }

    /**
     * 从文本中提取标题层级结构
     */
    private List<TitleSection> extractSections(String text, int maxLevel) {
        List<TitleSection> sections = new ArrayList<>();
        Matcher m = MD_HEADING.matcher(text);
        List<int[]> headingPositions = new ArrayList<>();

        while (m.find()) {
            int level = m.group(1).length();
            if (level <= maxLevel) {
                headingPositions.add(new int[]{m.start(), m.end(), level,
                        sections.size(), // index for parent lookup
                        m.group(2).length()}); // title text group
            }
        }

        if (headingPositions.isEmpty()) {
            sections.add(new TitleSection(1, "全文", text, null, null));
            return sections;
        }

        // 每个标题区间分割
        Deque<TitleSection> stack = new ArrayDeque<>();
        for (int i = 0; i < headingPositions.size(); i++) {
            int[] pos = headingPositions.get(i);
            int titleEnd = pos[1];
            int contentStart = titleEnd;
            int contentEnd = (i + 1 < headingPositions.size()) ? headingPositions.get(i + 1)[0] : text.length();

            String title = text.substring(pos[0], titleEnd).trim();
            String content = text.substring(contentStart, contentEnd).trim();
            int level = pos[2];

            // 找到父标题
            while (!stack.isEmpty() && stack.peek().level >= level) {
                stack.pop();
            }

            String parentId = stack.isEmpty() ? null : stack.peek().id;
            String parentTitle = stack.isEmpty() ? null : stack.peek().title;

            TitleSection section = new TitleSection(level, title, content, parentId, parentTitle);
            section.id = UUID.randomUUID().toString();
            sections.add(section);

            stack.push(section);
        }

        // 如果第一个标题之前有内容，作为序言
        if (!headingPositions.isEmpty() && headingPositions.get(0)[0] > 0) {
            String preContent = text.substring(0, headingPositions.get(0)[0]).trim();
            if (!preContent.isEmpty()) {
                TitleSection preamble = new TitleSection(0, "前言", preContent, null, null);
                preamble.id = UUID.randomUUID().toString();
                sections.add(0, preamble);
            }
        }

        return sections;
    }

    private String hash(String text) {
        int h = 0;
        for (char c : text.toCharArray()) h = 31 * h + c;
        return Integer.toHexString(h);
    }

    static class TitleSection {
        String id;
        int level;
        String title;
        String content;
        String parentId;
        String parentTitle;

        TitleSection(int level, String title, String content, String parentId, String parentTitle) {
            this.level = level;
            this.title = title;
            this.content = content;
            this.parentId = parentId;
            this.parentTitle = parentTitle;
        }
    }
}
