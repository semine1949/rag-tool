package com.rag.common.enums;

/**
 * 文件源类型
 */
public enum FileSourceType {
    /** 本地文件系统 */
    LOCAL,
    /** MinIO对象存储 */
    MINIO,
    /** URL远程文件 */
    REMOTE_URL,
    /** 直接上传的二进制流 */
    STREAM
}
