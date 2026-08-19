package com.twelvetimers.vector.dto;

/**
 * 文档详情：元信息 + 关联的向量化任务（任务与文档 1:1）。
 */
public record DocumentDetailResponse(DocumentInfo document, TaskBrief task) {
}
