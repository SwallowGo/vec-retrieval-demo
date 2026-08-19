package com.twelvetimers.vector.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 层参数校验与异常映射测试。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:vectordb_api;DB_CLOSE_DELAY=-1",
        "vector.embedding.simulated-delay-ms=0",
        "vector.embedding.busy-rounds=1000"
})
@AutoConfigureMockMvc
class ValidationApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("提交文档缺少 docId / 空文本 → 400 参数错误")
    void submitWithInvalidBodyReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "缺少 docId 的文档"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));

        mockMvc.perform(post("/api/v1/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"docId": "api-doc-1", "text": "   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));
    }

    @Test
    @DisplayName("检索 topK 越界 / 空文本 → 400；非法 JSON → 400")
    void searchWithInvalidParamsReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "查询文本", "topK": 0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));

        mockMvc.perform(post("/api/v1/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "查询文本", "topK": 150}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not-a-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));
    }

    @Test
    @DisplayName("提交文档成功 → 202 返回任务 ID；重复提交 → 409")
    void submitReturns202AndDuplicateReturns409() throws Exception {
        mockMvc.perform(post("/api/v1/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"docId": "api-doc-2", "text": "提交成功验证文本", "channel": "api"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").isNotEmpty());

        mockMvc.perform(post("/api/v1/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"docId": "api-doc-2", "text": "重复提交"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOC_DUPLICATE"));
    }

    @Test
    @DisplayName("任务/文档不存在 → 404 业务异常；文档详情正常")
    void notFoundReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/tasks/task-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/documents/doc-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOC_NOT_FOUND"));
    }

    @Test
    @DisplayName("失效不存在的文档 → 404；正常提交后可查询任务")
    void invalidateNotFoundReturns404() throws Exception {
        mockMvc.perform(post("/api/v1/documents/doc-not-exist/invalidate"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOC_NOT_FOUND"));
    }
}
