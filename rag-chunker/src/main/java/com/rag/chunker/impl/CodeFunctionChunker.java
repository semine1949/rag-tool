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
 * 代码函数分片策略
 * 按函数/方法边界切割代码块（识别Java/Python/JS/Go等函数关键字）
 */
public class CodeFunctionChunker implements TextChunker {

    private static final Logger log = LoggerFactory.getLogger(CodeFunctionChunker.class);

    // 多语言函数签名匹配模式
    private static final List<FunctionPattern> FUNCTION_PATTERNS = List.of(
            // Java/C#/C++ 方法声明
            new FunctionPattern("java", Pattern.compile(
                    "(public|private|protected|static|\\s)+[\\w<>\\[\\]]+\\s+(\\w+)\\s*\\([^)]*\\)\\s*(?:throws\\s+[\\w,]+)?\\s*\\{")),
            // Python def
            new FunctionPattern("python", Pattern.compile(
                    "^def\\s+(\\w+)\\s*\\([^)]*\\)\\s*:", Pattern.MULTILINE)),
            // JavaScript/TypeScript function
            new FunctionPattern("javascript", Pattern.compile(
                    "(?:function\\s+(\\w+)\\s*\\([^)]*\\)|(\\w+)\\s*=\\s*(?:async\\s+)?\\([^)]*\\)\\s*=>|(\\w+)\\s*\\([^)]*\\)\\s*\\{)")),
            // Go func
            new FunctionPattern("go", Pattern.compile(
                    "^func\\s+(?:\\([^)]+\\)\\s+)?(\\w+)\\s*\\([^)]*\\)", Pattern.MULTILINE)),
            // Rust fn
            new FunctionPattern("rust", Pattern.compile(
                    "^(?:pub\\s+)?fn\\s+(\\w+)\\s*\\([^)]*\\)", Pattern.MULTILINE))
    );

    @Override
    public List<Chunk> chunk(DocumentParseResult parseResult, ChunkConfig config) {
        if (!Boolean.TRUE.equals(config.getSplitCodeByFunction())) {
            return List.of();
        }

        List<CodeUnit> codeBlocks = parseResult.getCodeBlockList();
        if (codeBlocks == null || codeBlocks.isEmpty()) return List.of();

        List<Chunk> chunks = new ArrayList<>();
        for (CodeUnit code : codeBlocks) {
            String codeText = code.getContent();
            if (codeText == null || codeText.isEmpty()) continue;

            // 尝试按函数边界切分
            List<String> functions = splitByFunctions(codeText, code.getLanguage());
            if (functions.size() <= 1) {
                // 未找到函数边界，整个代码块作为一个chunk
                chunks.add(buildCodeChunk(codeText, code, parseResult));
            } else {
                for (String func : functions) {
                    if (!func.trim().isEmpty()) {
                        chunks.add(buildCodeChunk(func, code, parseResult));
                    }
                }
            }
        }

        log.debug("代码函数分片完成，生成{}个chunk", chunks.size());
        return chunks;
    }

    @Override
    public ChunkStrategyEnum getStrategy() {
        return ChunkStrategyEnum.CODE_FUNCTION;
    }

    private List<String> splitByFunctions(String code, String language) {
        for (FunctionPattern fp : FUNCTION_PATTERNS) {
            Matcher m = fp.pattern.matcher(code);
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
                // 如果前面有非函数内容也加上
                if (!ranges.isEmpty() && ranges.get(0)[0] > 0) {
                    parts.add(0, code.substring(0, ranges.get(0)[0]).trim());
                }
                return parts;
            }
        }
        return List.of(code);
    }

    private Chunk buildCodeChunk(String text, CodeUnit code, DocumentParseResult parseResult) {
        Map<String, Object> codeMeta = new HashMap<>();
        codeMeta.put("codeId", code.getCodeId());
        codeMeta.put("language", code.getLanguage());
        codeMeta.put("caption", code.getCaption());

        Map<String, Object> extraMeta = new HashMap<>();
        extraMeta.put("codeFlag", true);

        return Chunk.builder()
                .chunkId(UUID.randomUUID().toString())
                .text(text)
                .chunkType(ChunkStrategyEnum.CODE_FUNCTION)
                .textHash(hash(text))
                .fileName(parseResult.getMeta() != null ? parseResult.getMeta().getFileName() : null)
                .fileType(parseResult.getMeta() != null ? parseResult.getMeta().getFileType() : null)
                .fileId(parseResult.getMeta() != null ? parseResult.getMeta().getFileId() : null)
                .pageNo(code.getPageNo())
                .codeMeta(codeMeta)
                .extraMeta(extraMeta)
                .build();
    }

    private String hash(String text) {
        int h = 0;
        for (char c : text.toCharArray()) h = 31 * h + c;
        return Integer.toHexString(h);
    }

    private record FunctionPattern(String language, Pattern pattern) {}
}
