package com.rag.parser.impl;

import com.rag.core.api.DocumentParser;
import com.rag.core.entity.*;
import com.rag.core.enums.FileTypeEnum;
import com.rag.core.exception.RagException;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/**
 * 文本/Markdown/HTML文件解析器
 * 识别标题层级、代码块
 */
public class TextParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(TextParser.class);

    private static final Set<String> TEXT_TYPES = Set.of("txt");
    private static final Set<String> MARKDOWN_TYPES = Set.of("md", "markdown");
    private static final Set<String> HTML_TYPES = Set.of("html", "htm");

    @Override
    public DocumentParseResult parse(File file) {
        String ext = getExtension(file.getName()).toLowerCase();
        try {
            return switch (ext) {
                case "txt" -> parseText(file);
                case "md", "markdown" -> parseMarkdown(file);
                case "html", "htm" -> parseHtml(file);
                default -> parseText(file); // fallback
            };
        } catch (Exception e) {
            log.error("文本解析失败: {}", file.getName(), e);
            throw new RagException("RAG_PARSE_TEXT", "文本解析失败: " + e.getMessage(), e);
        }
    }

    @Override
    public DocumentParseResult parse(FileSource fileSource) {
        File temp = createTempFile(fileSource, getExtension(fileSource.getFileName()));
        try {
            return parse(temp);
        } finally {
            temp.delete();
        }
    }

    @Override
    public boolean support(String fileType) {
        String lower = fileType.toLowerCase();
        return TEXT_TYPES.contains(lower) || MARKDOWN_TYPES.contains(lower) || HTML_TYPES.contains(lower);
    }

    // ============ TXT解析 ============
    private DocumentParseResult parseText(File file) throws IOException {
        String content = Files.readString(file.toPath());
        return buildResult(file, "txt", content, List.of(), List.of(), List.of());
    }

    // ============ Markdown解析 ============
    private DocumentParseResult parseMarkdown(File file) throws IOException {
        String content = Files.readString(file.toPath());
        List<CodeUnit> codeBlocks = new ArrayList<>();
        List<TitleNode> titleTree = new ArrayList<>();
        Map<String, TitleNode> titleStack = new LinkedHashMap<>();

        Parser parser = Parser.builder().build();
        org.commonmark.node.Node document = parser.parse(content);

        document.accept(new AbstractVisitor() {
            @Override
            public void visit(Heading heading) {
                TitleNode node = TitleNode.builder()
                        .titleId(UUID.randomUUID().toString())
                        .text(getLiteralText(heading))
                        .level(heading.getLevel())
                        .children(new ArrayList<>())
                        .build();

                // 找到父标题
                TitleNode parent = null;
                for (int lv = heading.getLevel() - 1; lv >= 1; lv--) {
                    String key = "h" + lv;
                    if (titleStack.containsKey(key)) {
                        parent = titleStack.get(key);
                        break;
                    }
                }

                if (parent != null) {
                    node.setParentId(parent.getTitleId());
                    parent.getChildren().add(node);
                } else {
                    titleTree.add(node);
                }

                titleStack.put("h" + heading.getLevel(), node);
                // 清理更深层级的缓存
                titleStack.entrySet().removeIf(e ->
                        Integer.parseInt(e.getKey().substring(1)) > heading.getLevel());
            }

            @Override
            public void visit(FencedCodeBlock fencedCodeBlock) {
                codeBlocks.add(CodeUnit.builder()
                        .codeId(UUID.randomUUID().toString())
                        .content(fencedCodeBlock.getLiteral())
                        .language(fencedCodeBlock.getInfo())
                        .build());
            }

            @Override
            public void visit(IndentedCodeBlock indentedCodeBlock) {
                codeBlocks.add(CodeUnit.builder()
                        .codeId(UUID.randomUUID().toString())
                        .content(indentedCodeBlock.getLiteral())
                        .language("")
                        .build());
            }
        });

        return buildResult(file, "md", content, List.of(), codeBlocks, titleTree);
    }

    // ============ HTML解析 ============
    private DocumentParseResult parseHtml(File file) throws IOException {
        org.jsoup.nodes.Document doc = Jsoup.parse(file, "UTF-8");
        String bodyText = doc.body().text();

        List<CodeUnit> codeBlocks = new ArrayList<>();
        Elements pres = doc.select("pre, code");
        for (Element pre : pres) {
            String lang = pre.classNames().stream()
                    .filter(c -> c.startsWith("language-"))
                    .findFirst().map(c -> c.replace("language-", "")).orElse("");
            codeBlocks.add(CodeUnit.builder()
                    .codeId(UUID.randomUUID().toString())
                    .content(pre.text())
                    .language(lang)
                    .build());
        }

        List<TitleNode> titleTree = new ArrayList<>();
        Map<Integer, TitleNode> lastAtLevel = new HashMap<>();

        Elements headings = doc.select("h1, h2, h3, h4, h5, h6");
        for (Element h : headings) {
            int level = Integer.parseInt(h.tagName().substring(1));
            TitleNode node = TitleNode.builder()
                    .titleId(UUID.randomUUID().toString())
                    .text(h.text())
                    .level(level)
                    .children(new ArrayList<>())
                    .build();

            TitleNode parent = null;
            for (int lv = level - 1; lv >= 1; lv--) {
                parent = lastAtLevel.get(lv);
                if (parent != null) break;
            }

            if (parent != null) {
                node.setParentId(parent.getTitleId());
                parent.getChildren().add(node);
            } else {
                titleTree.add(node);
            }
            lastAtLevel.put(level, node);
        }

        return buildResult(file, "html", bodyText, List.of(), codeBlocks, titleTree);
    }

    private String getLiteralText(org.commonmark.node.Node node) {
        StringBuilder sb = new StringBuilder();
        node.accept(new AbstractVisitor() {
            @Override
            public void visit(Text text) {
                sb.append(text.getLiteral());
            }
        });
        return sb.toString().trim();
    }

    private DocumentParseResult buildResult(File file, String fileType, String fullText,
                                            List<TableUnit> tables, List<CodeUnit> codeBlocks,
                                            List<TitleNode> titleTree) {
        DocumentMeta meta = DocumentMeta.builder()
                .fileName(file.getName())
                .fileType(fileType)
                .fileSize(file.length())
                .fileId(UUID.randomUUID().toString())
                .sourcePath(file.getAbsolutePath())
                .build();
        return DocumentParseResult.builder()
                .fullText(fullText)
                .tableList(tables)
                .codeBlockList(codeBlocks)
                .titleTree(titleTree)
                .meta(meta)
                .build();
    }

    private String getExtension(String fileName) {
        int i = fileName.lastIndexOf('.');
        return i > 0 ? fileName.substring(i + 1).toLowerCase() : "txt";
    }

    private File createTempFile(FileSource fileSource, String ext) {
        try {
            File temp = File.createTempFile("rag_text_", "." + ext);
            Files.write(temp.toPath(), fileSource.getContent());
            return temp;
        } catch (IOException e) {
            throw new RagException("RAG_FILE_ERR", "创建临时文件失败", e);
        }
    }
}
