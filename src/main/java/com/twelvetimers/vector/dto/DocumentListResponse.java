package com.twelvetimers.vector.dto;

import java.util.List;

/**
 * 文档列表响应（分页 + 渠道/状态过滤）。
 */
public record DocumentListResponse(List<DocumentInfo> items, int page, int size, long total) {
}
