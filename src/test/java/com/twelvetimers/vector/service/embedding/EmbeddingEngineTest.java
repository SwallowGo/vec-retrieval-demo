package com.twelvetimers.vector.service.embedding;

import com.twelvetimers.vector.entity.DocumentEntity;
import com.twelvetimers.vector.entity.DocumentStatus;
import com.twelvetimers.vector.entity.TaskStatus;
import com.twelvetimers.vector.entity.VectorTaskEntity;
import com.twelvetimers.vector.repository.DocumentRepository;
import com.twelvetimers.vector.repository.VectorTaskRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 异步引擎集成测试：异步完成、失败路径、启动恢复。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:vectordb_test;DB_CLOSE_DELAY=-1",
        "vector.embedding.simulated-delay-ms=0",
        "vector.embedding.busy-rounds=1000"
})
class EmbeddingEngineTest {

    @Autowired
    private EmbeddingEngine engine;
    @Autowired
    private DocumentRepository documentRepository;
    @Autowired
    private VectorTaskRepository taskRepository;

    @Test
    @DisplayName("提交任务后异步完成：任务 SUCCESS、文档 READY 且向量落库")
    void submitTaskCompletesAsynchronously() {
        DocumentEntity document = newDocument("doc-engine-1", "异步引擎集成测试文本", "default");
        VectorTaskEntity task = newTask(document.getDocId());

        engine.submit(task);
        await(() -> taskRepository.findByTaskId(task.getTaskId())
                .map(t -> t.getStatus() == TaskStatus.SUCCESS).orElse(false));

        DocumentEntity loaded = documentRepository.findByDocId("doc-engine-1").orElseThrow();
        assertEquals(DocumentStatus.READY, loaded.getStatus());
        assertNotNull(loaded.getVector());
        assertEquals(VectorCalculator.DIMENSIONS * Float.BYTES, loaded.getVector().length);
        assertNotNull(loaded.getCompleteTime());

        VectorTaskEntity loadedTask = taskRepository.findByTaskId(task.getTaskId()).orElseThrow();
        assertNotNull(loadedTask.getStartTime());
        assertNotNull(loadedTask.getFinishTime());
    }

    @Test
    @DisplayName("文档不存在时任务标记 FAILED 并记录原因")
    void submitTaskFailsWhenDocumentMissing() {
        VectorTaskEntity task = newTask("doc-not-exist");

        engine.submit(task);
        await(() -> taskRepository.findByTaskId(task.getTaskId())
                .map(t -> t.getStatus() == TaskStatus.FAILED).orElse(false));

        VectorTaskEntity loaded = taskRepository.findByTaskId(task.getTaskId()).orElseThrow();
        assertNotNull(loaded.getErrorMsg());
        assertNotNull(loaded.getFinishTime());
    }

    @Test
    @DisplayName("启动恢复：遗留 QUEUED 任务重置并重新入队，最终完成")
    void recoverUnfinishedTasksReplaysQueuedTasks() {
        DocumentEntity document = newDocument("doc-engine-3", "启动恢复验证文本", "default");
        VectorTaskEntity task = newTask(document.getDocId());
        // 模拟进程中断：任务已持久化为 QUEUED 但未入队
        engine.recoverUnfinishedTasks();

        await(() -> taskRepository.findByTaskId(task.getTaskId())
                .map(t -> t.getStatus() == TaskStatus.SUCCESS).orElse(false));
        assertEquals(DocumentStatus.READY,
                documentRepository.findByDocId("doc-engine-3").orElseThrow().getStatus());
    }

    private DocumentEntity newDocument(String docId, String text, String channel) {
        DocumentEntity document = new DocumentEntity();
        document.setDocId(docId);
        document.setText(text);
        document.setChannel(channel);
        document.setStatus(DocumentStatus.PENDING);
        document.setSubmitTime(LocalDateTime.now());
        return documentRepository.save(document);
    }

    private VectorTaskEntity newTask(String docId) {
        VectorTaskEntity task = new VectorTaskEntity();
        task.setTaskId(UUID.randomUUID().toString());
        task.setDocId(docId);
        task.setStatus(TaskStatus.QUEUED);
        task.setCreateTime(LocalDateTime.now());
        return taskRepository.save(task);
    }

    /** 轮询等待异步条件成立（上限 10 秒） */
    private static void await(Supplier<Boolean> condition) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.get()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertTrue(condition.get(), "10 秒内条件未满足");
    }
}
