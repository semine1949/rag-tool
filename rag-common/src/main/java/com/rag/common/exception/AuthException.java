package com.rag.common.exception;

/**
 * 认证鉴权异常
 */
public class AuthException extends RuntimeException {

    private final String errorCode;

    public AuthException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public AuthException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
