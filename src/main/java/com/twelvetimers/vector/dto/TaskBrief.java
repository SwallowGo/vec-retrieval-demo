package com.twelvetimers.vector.dto;

import com.twelvetimers.vector.entity.VectorTaskEntity;

import java.time.LocalDateTime;

/**
 * 向量化任务简要信息。
 */
public record TaskBrief(
        String taskId,
        String status,
        String errorMsg,
        LocalDateTime createTime,
        LocalDateTime startTime,
        LocalDateTime finishTime) {

    public static TaskBrief from(VectorTaskEntity task) {
        return new TaskBrief(
                task.getTaskId(),
                task.getStatus().name(),
                task.getErrorMsg(),
                task.getCreateTime(),
                task.getStartTime(),
                task.getFinishTime());
    }
}
