package com.twelvetimers.vector.dto;

/**
 * 文档入库请求。渠道可选，不传默认 default。
 */
public record SubmitDocumentRequest(String docId, String text, String channel) {
}
