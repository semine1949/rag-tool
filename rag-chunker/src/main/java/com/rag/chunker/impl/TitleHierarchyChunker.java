package com.rag.chunker.impl;

import com.rag.chunker.ConfigurableTextSplitter;
import com.rag.chunker.util.ChunkDocuments;
import com.rag.core.config.ChunkConfig;
import com.rag.core.enums.ChunkStrategyEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 标题层级分片策略：按 Markdown/HTML 标题层级递归切割，父子标题绑定元数据。
 * 实现 Spring AI {@link ConfigurableTextSplitter}，产出 {@link Document} 列表。
 */
public class TitleHierarchyChunker extends ConfigurableTextSplitter {

    private static final Logger log = LoggerFactory.getLogger(TitleHierarchyChunker.class);

    private static final Pattern MD_HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);

    private ChunkConfig config;

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
        int maxLevel = config != null && config.getMaxTitleLevel() != null ? config.getMaxTitleLevel() : 3;

        List<TitleSection> sections = extractSections(text, maxLevel);
        if (sections.size() <= 1) {
            return List.of();
        }

        Document input = documents.get(0);
        List<Document> chunks = new ArrayList<>();
        for (TitleSection section : sections) {
            if (section.content == null || section.content.trim().isEmpty()) {
                continue;
            }
            Map<String, Object> titleMeta = new HashMap<>();
            titleMeta.put("level", section.level);
            titleMeta.put("title", section.title);
            titleMeta.put("parentTitle", section.parentTitle);

            Map<String, Object> extra = new HashMap<>();
            extra.put("titleMeta", titleMeta);
            if (section.parentId != null) {
                extra.put("parentId", section.parentId);
            }
            chunks.add(ChunkDocuments.of(input, section.title + "\n" + section.content.trim(),
                    ChunkStrategyEnum.TITLE_HIERARCHY.name(), extra));
        }

        log.debug("标题层级分片完成，生成{}个chunk", chunks.size());
        return chunks;
    }

    private List<TitleSection> extractSections(String text, int maxLevel) {
        List<TitleSection> sections = new ArrayList<>();
        Matcher m = MD_HEADING.matcher(text);
        List<int[]> headingPositions = new ArrayList<>();

        while (m.find()) {
            int level = m.group(1).length();
            if (level <= maxLevel) {
                headingPositions.add(new int[]{m.start(), m.end(), level});
            }
        }

        if (headingPositions.isEmpty()) {
            sections.add(new TitleSection(1, "全文", text, null, null));
            return sections;
        }

        Deque<TitleSection> stack = new ArrayDeque<>();
        for (int i = 0; i < headingPositions.size(); i++) {
            int[] pos = headingPositions.get(i);
            int titleEnd = pos[1];
            int contentStart = titleEnd;
            int contentEnd = (i + 1 < headingPositions.size()) ? headingPositions.get(i + 1)[0] : text.length();

            String title = text.substring(pos[0], titleEnd).trim();
            String content = text.substring(contentStart, contentEnd).trim();
            int level = pos[2];

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
