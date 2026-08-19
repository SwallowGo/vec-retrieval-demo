package com.twelvetimers.vector.service;

import com.twelvetimers.vector.dto.DocumentInfo;
import com.twelvetimers.vector.dto.TaskInfoResponse;
import com.twelvetimers.vector.entity.VectorTaskEntity;
import com.twelvetimers.vector.exception.BusinessException;
import com.twelvetimers.vector.exception.ErrorCode;
import com.twelvetimers.vector.repository.DocumentRepository;
import com.twelvetimers.vector.repository.VectorTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 任务状态查询。
 */
@Service
@RequiredArgsConstructor
public class TaskService {

    private final VectorTaskRepository taskRepository;
    private final DocumentRepository documentRepository;

    /** 按任务 ID 查询状态；任务完成后附带文档信息 */
    public TaskInfoResponse getTask(String taskId) {
        VectorTaskEntity task = taskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND,
                        "任务不存在: " + taskId));
        DocumentInfo document = documentRepository.findByDocId(task.getDocId())
                .map(DocumentInfo::from).orElse(null);
        return new TaskInfoResponse(
                task.getTaskId(),
                task.getDocId(),
                task.getStatus().name(),
                task.getErrorMsg(),
                task.getCreateTime(),
                task.getStartTime(),
                task.getFinishTime(),
                document);
    }
}
