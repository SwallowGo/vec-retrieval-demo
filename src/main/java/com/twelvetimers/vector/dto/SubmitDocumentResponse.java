package com.twelvetimers.vector.dto;

/**
 * 文档入库响应：仅返回任务 ID，向量化在后台异步执行。
 */
public record SubmitDocumentResponse(String taskId) {
}
