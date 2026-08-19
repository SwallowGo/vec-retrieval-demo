package com.twelvetimers.vector.dto;

import java.time.LocalDateTime;

/**
 * 任务状态查询响应。任务完成后 document 字段携带文档信息。
 */
public record TaskInfoResponse(
        String taskId,
        String docId,
        String status,
        String errorMsg,
        LocalDateTime createTime,
        LocalDateTime startTime,
        LocalDateTime finishTime,
        DocumentInfo document) {
}
