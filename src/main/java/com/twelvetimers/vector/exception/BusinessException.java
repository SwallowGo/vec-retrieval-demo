package com.twelvetimers.vector.exception;

import lombok.Getter;

/**
 * 业务异常：携带错误码，由全局异常处理器统一转换为 HTTP 响应。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
