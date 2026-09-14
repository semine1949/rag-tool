package com.rag.common.parser.impl;

import com.rag.common.exception.RagException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;

import java.io.File;
import java.util.List;

/**
 * MinerU 文档解析器（PDF 默认解析器，路由骨架实现）。
 * <p>
 * <b>定位</b>：PDF 的默认解析器；亦可通过上传参数 {@code modelName=minerU} 强制覆盖任意文件类型。
 * <p>
 * <b>当前状态：路由占位骨架</b>——仅完成路由接入与接口契约，未实现实际解析逻辑。
 * 远程 / 本地 MinerU 服务调用逻辑待后续任务实现（见 {@link #get()} 内 TODO）。
 * <p>
 * <b>计划接入方式</b>（二选一，待确认后实现）：
 * <ul>
 *   <li>远程 MinerU API：复用 {@code OpenAiClient} 的 HTTP 调用模式，直连远端服务</li>
 *   <li>本地 MinerU 服务：Java 侧 HTTP 调用（如 http://localhost:8000）</li>
 * </ul>
 * <p>
 * <b>设计取舍</b>：骨架阶段解析方法直接抛异常，而非返回空内容，
 * 避免「路由命中但产出空内容」静默污染向量库；异常信息明确指向未实现，便于快速定位。
 */
public class MinerUParser implements DocumentReader {

    private static final Logger log = LoggerFactory.getLogger(MinerUParser.class);

    /** 来源类型标记（写入 Document metadata.sourceType，便于检索溯源解析器） */
    public static final String SOURCE_TYPE = "mineru";

    /** 待解析文件 */
    private final File file;

    public MinerUParser(File file) {
        this.file = file;
    }

    /**
     * 解析文档为 Document 列表。
     * <p>骨架阶段：仅打印路由命中日志后抛出未实现异常。</p>
     *
     * @return Document 列表
     * @throws RagException 骨架阶段固定抛出 RAG_PARSE_MINERU_UNIMPLEMENTED
     */
    @Override
    public List<Document> get() {
        log.info("MinerU 解析器路由命中: file={}, size={} bytes", file.getName(), file.length());

        // TODO 待实现：调用 MinerU 服务解析文档，产出结构化 Document 列表
        // TODO 步骤1：将文件提交至 MinerU 解析端点（/file_parse 或等价端点），同步或轮询获取结果
        // TODO 步骤2：解析返回的 markdown / content_list 结构化内容
        // TODO 步骤3：按页或按段落产出 Document，metadata 写入
        // TODO        fileName / fileType / fileSize / sourcePath / sourceType=SOURCE_TYPE(mineru)
        // 骨架阶段抛异常，避免产出空内容污染向量库
        throw new RagException("RAG_PARSE_MINERU_UNIMPLEMENTED",
                "MinerU 解析器尚未实现，暂无法解析文件: " + file.getName());
    }
}
