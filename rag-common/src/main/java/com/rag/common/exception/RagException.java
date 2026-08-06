package com.rag.common.exception;

/**
 * RAG工具通用异常
 */
public class RagException extends RuntimeException {

    private final String errorCode;

    public RagException(String message) {
        super(message);
        this.errorCode = "RAG_ERR";
    }

    public RagException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public RagException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
