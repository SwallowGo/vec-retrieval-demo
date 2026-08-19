package com.twelvetimers.vector.dto;

import java.util.List;

/**
 * 向量检索响应：相似度降序的 Top-K 文档。
 */
public record SearchResponse(List<SearchHitItem> items) {
}
