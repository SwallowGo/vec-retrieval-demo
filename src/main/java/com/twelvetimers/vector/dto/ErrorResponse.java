package com.twelvetimers.vector.dto;

import java.time.Instant;

/**
 * 统一错误响应体。
 */
public record ErrorResponse(String code, String message, Instant timestamp) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, Instant.now());
    }
}
