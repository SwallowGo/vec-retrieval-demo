package com.twelvetimers.vector.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 业务错误码：HTTP 状态 + 默认消息。
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    INVALID_PARAM(400, "参数错误"),
    DOC_NOT_FOUND(404, "文档不存在"),
    TASK_NOT_FOUND(404, "任务不存在"),
    PATH_NOT_FOUND(404, "请求路径不存在"),
    METHOD_NOT_ALLOWED(405, "请求方法不允许"),
    MEDIA_TYPE_NOT_SUPPORTED(415, "不支持的媒体类型"),
    DOC_DUPLICATE(409, "文档已存在"),
    DOC_STATUS_NOT_ALLOWED(409, "文档状态不允许该操作"),
    QUEUE_FULL(503, "向量化队列已满，请稍后重试"),
    INTERNAL_ERROR(500, "系统内部错误");

    private final int httpStatus;
    private final String defaultMessage;
}
