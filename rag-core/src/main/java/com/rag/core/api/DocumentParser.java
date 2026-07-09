package com.rag.core.api;

import com.rag.core.entity.DocumentParseResult;
import com.rag.core.entity.FileSource;

import java.io.File;

/**
 * 文档解析器顶层接口
 */
public interface DocumentParser {

    /**
     * 解析文件，返回统一结果
     * @param file 文件对象
     * @return 解析结果
     */
    DocumentParseResult parse(File file);

    /**
     * 解析文件源，返回统一结果
     * @param fileSource 文件源对象
     * @return 解析结果
     */
    DocumentParseResult parse(FileSource fileSource);

    /**
     * 当前解析器支持的文件类型
     * @param fileType 文件扩展名
     * @return 是否支持
     */
    boolean support(String fileType);
}
