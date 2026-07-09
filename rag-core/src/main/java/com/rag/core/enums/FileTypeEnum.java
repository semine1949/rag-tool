package com.rag.core.enums;

/**
 * 文件类型枚举
 */
public enum FileTypeEnum {
    PDF("pdf"),
    DOCX("docx"),
    DOC("doc"),
    PPTX("pptx"),
    PPT("ppt"),
    XLSX("xlsx"),
    XLS("xls"),
    TXT("txt"),
    MD("md"),
    MARKDOWN("markdown"),
    HTML("html"),
    HTM("htm"),
    JPG("jpg"),
    JPEG("jpeg"),
    PNG("png"),
    BMP("bmp"),
    UNKNOWN("unknown");

    private final String extension;

    FileTypeEnum(String extension) {
        this.extension = extension;
    }

    public String getExtension() {
        return extension;
    }

    public static FileTypeEnum fromExtension(String ext) {
        if (ext == null) return UNKNOWN;
        for (FileTypeEnum type : values()) {
            if (type.extension.equalsIgnoreCase(ext)) {
                return type;
            }
        }
        return UNKNOWN;
    }
}
