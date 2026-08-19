package com.twelvetimers.vector.dto;

/**
 * 向量检索请求：查询文本、topK、可选渠道过滤。检索为同步接口。
 */
public record SearchRequest(String text, Integer topK, String channel) {
}
