package com.rag.common.parser;

import com.rag.common.enums.FileTypeEnum;
import com.rag.common.exception.RagException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * 文档解析工厂 —— 按「modelName 显式指定 &gt; 扩展名 + 配置」两优先级路由解析器。
 * <p>
 * <b>路由规则</b>（优先级从高到低）：
 * <ol>
 *   <li>{@code modelName=minerU}（忽略大小写）→ 强制 {@link com.rag.common.parser.impl.MinerUParser}，
 *       覆盖任意文件类型</li>
 *   <li>按扩展名匹配 {@link #readerSuppliers}；其中 PDF 的 supplier 由装配层依据配置项
 *       {@code rag.parser.pdf-provider} 决定（mineru / tika-mixed）</li>
 *   <li>扩展名未匹配时回退 TXT supplier；仍无则抛出不支持异常</li>
 * </ol>
 * <p>
 * 解析后统一补充 fileId 元数据（单文件内所有切片共享，便于按文件删除向量）。
 */
public class DocumentParseFactory {

    private static final Logger log = LoggerFactory.getLogger(DocumentParseFactory.class);

    /** modelName 命中该值（忽略大小写）时强制使用 MinerU 解析器 */
    private static final String MINERU_MODEL_NAME = "mineru";

    /** 扩展名 → 解析器 supplier 映射（PDF 键的取值受配置影响） */
    private final Map<FileTypeEnum, Function<File, DocumentReader>> readerSuppliers;

    /**
     * 强制 MinerU 时使用的 supplier。
     * <p>不能复用 {@link #readerSuppliers} 的 PDF 键：该键受 {@code rag.parser.pdf-provider}
     * 配置影响（可能为 tika-mixed），且 modelName=minerU 需覆盖任意扩展名。</p>
     */
    private final Function<File, DocumentReader> mineruReaderSupplier;

    public DocumentParseFactory(Map<FileTypeEnum, Function<File, DocumentReader>> readerSuppliers,
                                Function<File, DocumentReader> mineruReaderSupplier) {
        this.readerSuppliers = readerSuppliers;
        this.mineruReaderSupplier = mineruReaderSupplier;
    }

    /**
     * 按扩展名自动路由解析（向后兼容入口，等价于 {@code parse(file, null)}）。
     *
     * @param file 待解析文件
     * @return Document 列表
     */
    public List<Document> parse(File file) {
        return parse(file, null);
    }

    /**
     * 按 modelName + 扩展名路由解析。
     *
     * @param file      待解析文件
     * @param modelName 解析模型名（可空）。传 {@code minerU}（忽略大小写）时强制使用 MinerU 解析器
     * @return Document 列表
     */
    public List<Document> parse(File file, String modelName) {
        String ext = getFileExtension(file.getName());

        // 优先级 1：显式指定 modelName=minerU → 强制 MinerU（覆盖任意文件类型）
        if (isMineru(modelName)) {
            log.info("解析器路由: modelName=minerU 强制覆盖, file={}, ext={}", file.getName(), ext);
            return doParse(file, mineruReaderSupplier, ext);
        }

        // 优先级 2：按扩展名路由（PDF 的 supplier 已由 rag.parser.pdf-provider 配置决定）
        FileTypeEnum type = FileTypeEnum.fromExtension(ext);
        Function<File, DocumentReader> supplier = readerSuppliers.get(type);
        if (supplier == null) {
            log.warn("解析器路由: 扩展名 {} 未匹配({}), 回退 TXT 解析器", ext, type);
            supplier = readerSuppliers.get(FileTypeEnum.TXT);
        }
        if (supplier == null) {
            throw new RagException("RAG_PARSE_001", "不支持的文件类型: " + ext);
        }
        log.info("解析器路由: 按扩展名匹配, file={}, ext={}, type={}", file.getName(), ext, type);
        return doParse(file, supplier, ext);
    }

    /**
     * 执行解析并统一补充 fileId 元数据（两条路由共用）。
     *
     * @param file     待解析文件
     * @param supplier 解析器 supplier
     * @param ext      文件扩展名（仅用于日志）
     * @return Document 列表
     */
    private List<Document> doParse(File file, Function<File, DocumentReader> supplier, String ext) {
        List<Document> docs = supplier.apply(file).get();
        if (docs.isEmpty()) {
            throw new RagException("RAG_PARSE_002", "解析结果为空: " + file.getName());
        }
        String fileId = UUID.randomUUID().toString();
        for (Document doc : docs) {
            doc.getMetadata().putIfAbsent("fileId", fileId);
        }
        log.debug("解析完成: file={}, ext={}, 段落数={}", file.getName(), ext, docs.size());
        return docs;
    }

    /**
     * 判断 modelName 是否显式指定使用 MinerU 解析器。
     * <p>忽略大小写与首尾空格，兼容 minerU / MINERU / mineru 等写法。</p>
     *
     * @param modelName 解析模型名（可空）
     * @return true 表示强制使用 MinerU 解析器
     */
    private boolean isMineru(String modelName) {
        return modelName != null && MINERU_MODEL_NAME.equalsIgnoreCase(modelName.trim());
    }

    /**
     * 提取文件名扩展名（不含点，保留原始大小写）。
     *
     * @param fileName 文件名
     * @return 扩展名；无扩展名时返回空串
     */
    private String getFileExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf(".") + 1);
    }
}
