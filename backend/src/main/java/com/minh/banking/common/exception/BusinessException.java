package com.minh.banking.common.exception;

import org.springframework.http.HttpStatus;

public class BusinessException extends RuntimeException {
    private final ErrorCode code;
    private final HttpStatus status;

    public BusinessException(String code, String message, HttpStatus status) {
        super(message);
        this.code = ErrorCode.valueOf(code);
        if (this.code.status() != status) throw new IllegalArgumentException("Error code/status mismatch");
        this.status = status;
    }

    public BusinessException(ErrorCode code, String message) {
        this(code.name(), message, code.status());
    }

    public String getCode() { return code.name(); }
    public HttpStatus getStatus() { return status; }
}
