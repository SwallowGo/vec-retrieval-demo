package com.twelvetimers.vector.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 根路径欢迎页：浏览器直接访问服务地址时返回服务信息与接口清单。
 */
@RestController
public class IndexController {

    @GetMapping("/")
    public Map<String, Object> index() {
        return Map.of(
                "service", "12timers 文本模拟向量化与向量检索微服务",
                "status", "running",
                "endpoints", List.of(
                        "POST /api/v1/documents                   文档入库（异步向量化，返回任务 ID）",
                        "GET  /api/v1/tasks/{taskId}              任务状态查询",
                        "POST /api/v1/search                     向量检索（同步，Top-K + 渠道过滤）",
                        "POST /api/v1/documents/{docId}/invalidate 标记文档失效",
                        "GET  /api/v1/documents                   文档列表（分页+过滤）",
                        "GET  /api/v1/documents/{docId}           文档详情"
                ));
    }
}
