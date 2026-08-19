package com.twelvetimers.vector.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 文档入库请求。渠道可选，不传默认 default。
 */
public record SubmitDocumentRequest(
        @NotBlank(message = "docId 不能为空")
        @Size(max = 64, message = "docId 长度不能超过 64")
        String docId,

        @NotBlank(message = "text 不能为空")
        String text,

        @Size(max = 32, message = "channel 长度不能超过 32")
        String channel) {
}
