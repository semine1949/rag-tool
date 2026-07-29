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
 * 代码函数分片策略：按函数/方法边界切割代码块（识别 Java/Python/JS/Go/Rust 等函数关键字）。
 * 实现 Spring AI {@link ConfigurableTextSplitter}，产出 {@link Document} 列表。
 */
public class CodeFunctionChunker extends ConfigurableTextSplitter {

    private static final Logger log = LoggerFactory.getLogger(CodeFunctionChunker.class);

    private static final Pattern FENCE = Pattern.compile("```(\\w*)\\n([\\s\\S]*?)```");

    private static final List<FunctionPattern> FUNCTION_PATTERNS = List.of(
            new FunctionPattern("java", Pattern.compile(
                    "(public|private|protected|static|\\s)+[\\w<>\\[\\]]+\\s+(\\w+)\\s*\\([^)]*\\)\\s*(?:throws\\s+[\\w,]+)?\\s*\\{")),
            new FunctionPattern("python", Pattern.compile("^def\\s+(\\w+)\\s*\\([^)]*\\)\\s*:", Pattern.MULTILINE)),
            new FunctionPattern("javascript", Pattern.compile(
                    "(?:function\\s+(\\w+)\\s*\\([^)]*\\)|(\\w+)\\s*=\\s*(?:async\\s+)?\\([^)]*\\)\\s*=>|(\\w+)\\s*\\([^)]*\\)\\s*\\{)")),
            new FunctionPattern("go", Pattern.compile("^func\\s+(?:\\([^)]+\\)\\s+)?(\\w+)\\s*\\([^)]*\\)", Pattern.MULTILINE)),
            new FunctionPattern("rust", Pattern.compile("^(?:pub\\s+)?fn\\s+(\\w+)\\s*\\([^)]*\\)", Pattern.MULTILINE))
    );

    private ChunkConfig config;

    @Override
    public void setConfig(ChunkConfig config) {
        this.config = config;
    }

    @Override
    public List<Document> apply(List<Document> documents) {
        if (config == null || !Boolean.TRUE.equals(config.getSplitCodeByFunction())) {
            return List.of();
        }
        String text = ChunkDocuments.fullText(documents);
        if (text.isEmpty()) {
            return List.of();
        }
        Document input = documents.get(0);
        List<Document> chunks = new ArrayList<>();
        for (CodeBlock block : extractFencedCodeBlocks(text)) {
            List<String> functions = splitByFunctions(block.content(), block.lang());
            List<String> parts = functions.size() <= 1 ? List.of(block.content()) : functions;
            for (String part : parts) {
                if (part.trim().isEmpty()) {
                    continue;
                }
                Map<String, Object> codeMeta = new HashMap<>();
                codeMeta.put("language", block.lang());
                Map<String, Object> extra = new HashMap<>();
                extra.put("codeFlag", true);
                extra.put("codeMeta", codeMeta);
                chunks.add(ChunkDocuments.of(input, part, ChunkStrategyEnum.CODE_FUNCTION.name(), extra));
            }
        }
        log.debug("代码函数分片完成，生成{}个chunk", chunks.size());
        return chunks;
    }

    private List<CodeBlock> extractFencedCodeBlocks(String text) {
        List<CodeBlock> blocks = new ArrayList<>();
        Matcher m = FENCE.matcher(text);
        while (m.find()) {
            blocks.add(new CodeBlock(m.group(1).isEmpty() ? "unknown" : m.group(1), m.group(2)));
        }
        return blocks;
    }

    private List<String> splitByFunctions(String code, String language) {
        for (FunctionPattern fp : FUNCTION_PATTERNS) {
            if (!fp.language().equals(language) && !"unknown".equals(language)) {
                continue;
            }
            Matcher m = fp.pattern().matcher(code);
            List<int[]> ranges = new ArrayList<>();
            while (m.find()) {
                ranges.add(new int[]{m.start(), m.end()});
            }
            if (ranges.size() > 1) {
                List<String> parts = new ArrayList<>();
                for (int i = 0; i < ranges.size(); i++) {
                    int start = ranges.get(i)[0];
                    int end = (i + 1 < ranges.size()) ? ranges.get(i + 1)[0] : code.length();
                    parts.add(code.substring(start, end).trim());
                }
                if (!ranges.isEmpty() && ranges.get(0)[0] > 0) {
                    parts.add(0, code.substring(0, ranges.get(0)[0]).trim());
                }
                return parts;
            }
        }
        return List.of(code);
    }

    private record CodeBlock(String lang, String content) {
    }

    private record FunctionPattern(String language, Pattern pattern) {
    }
}
