package com.twelvetimers.vector.dto;

import com.twelvetimers.vector.entity.DocumentEntity;
import com.twelvetimers.vector.entity.DocumentStatus;

import java.time.LocalDateTime;

/**
 * 文档元信息（列表项/详情/任务关联文档）。
 */
public record DocumentInfo(
        String docId,
        String channel,
        String status,
        boolean vectorReady,
        long hitCount,
        LocalDateTime submitTime,
        LocalDateTime completeTime,
        LocalDateTime invalidTime) {

    public static DocumentInfo from(DocumentEntity document) {
        return new DocumentInfo(
                document.getDocId(),
                document.getChannel(),
                document.getStatus().name(),
                document.getStatus() == DocumentStatus.READY && document.getVector() != null,
                document.getHitCount(),
                document.getSubmitTime(),
                document.getCompleteTime(),
                document.getInvalidTime());
    }
}
