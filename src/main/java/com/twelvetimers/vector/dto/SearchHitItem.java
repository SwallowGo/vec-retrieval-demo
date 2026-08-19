package com.twelvetimers.vector.dto;

/**
 * 检索命中条目。
 *
 * @param docId  文档 ID
 * @param channel 渠道
 * @param score  余弦相似度，降序返回
 */
public record SearchHitItem(String docId, String channel, double score) {
}
