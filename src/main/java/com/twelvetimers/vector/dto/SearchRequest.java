package com.twelvetimers.vector.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 向量检索请求：查询文本、topK、可选渠道过滤。检索为同步接口。
 */
public record SearchRequest(
        @NotBlank(message = "text 不能为空")
        String text,

        @Min(value = 1, message = "topK 最小为 1")
        @Max(value = 100, message = "topK 最大为 100")
        Integer topK,

        @Size(max = 32, message = "channel 长度不能超过 32")
        String channel) {
}
