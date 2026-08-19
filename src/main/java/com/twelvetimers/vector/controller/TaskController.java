package com.twelvetimers.vector.controller;

import com.twelvetimers.vector.dto.TaskInfoResponse;
import com.twelvetimers.vector.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 向量化任务状态查询 API。
 */
@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    @GetMapping("/{taskId}")
    public TaskInfoResponse getTask(@PathVariable String taskId) {
        return taskService.getTask(taskId);
    }
}
