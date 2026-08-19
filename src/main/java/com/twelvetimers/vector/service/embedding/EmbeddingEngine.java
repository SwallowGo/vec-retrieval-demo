package com.twelvetimers.vector.service.embedding;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.twelvetimers.vector.config.EmbeddingProperties;
import com.twelvetimers.vector.entity.TaskStatus;
import com.twelvetimers.vector.entity.VectorTaskEntity;
import com.twelvetimers.vector.repository.VectorTaskRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 异步向量化引擎（纯 Java 实现，无中间件）：
 *
 * <ul>
 *   <li>生产者：{@link #submit} 把已持久化的任务投递到有界阻塞队列；</li>
 *   <li>消费者：固定线程池中的工作线程依次消费执行；</li>
 *   <li>背压：队满时 AbortPolicy 抛出 {@link RejectedExecutionException}，
 *       由上层标记任务失败并返回业务异常，不假排队；</li>
 *   <li>恢复：应用启动时将遗留的 QUEUED/PROCESSING 任务重置为 QUEUED 重放
 *       （向量化确定性幂等，重放安全）。</li>
 * </ul>
 */
@Component
@Slf4j
public class EmbeddingEngine {

    private final ThreadPoolExecutor executor;
    private final EmbeddingProcessor processor;
    private final VectorTaskRepository taskRepository;

    public EmbeddingEngine(EmbeddingProperties properties,
                           EmbeddingProcessor processor,
                           VectorTaskRepository taskRepository) {
        this.processor = processor;
        this.taskRepository = taskRepository;
        this.executor = new ThreadPoolExecutor(
                properties.workers(),
                properties.workers(),
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.queueCapacity()),
                new ThreadFactoryBuilder().setNameFormat("embedding-worker-%d").build(),
                new ThreadPoolExecutor.AbortPolicy());
        log.info("向量化引擎启动：workers={}, queueCapacity={}",
                properties.workers(), properties.queueCapacity());
    }

    /**
     * 提交任务（任务需已持久化）。队满抛 {@link RejectedExecutionException}。
     */
    public void submit(VectorTaskEntity task) {
        executor.execute(new EmbeddingWorker(task, processor));
    }

    /** 当前排队中的任务数（监控/测试用） */
    public int queuedCount() {
        return executor.getQueue().size();
    }

    /**
     * 启动恢复：把中断前遗留的 QUEUED/PROCESSING 任务重置为 QUEUED 并重新入队。
     * 向量化结果由文本唯一决定，重复执行幂等。
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recoverUnfinishedTasks() {
        List<VectorTaskEntity> unfinished = taskRepository.findByStatusIn(
                List.of(TaskStatus.QUEUED, TaskStatus.PROCESSING));
        if (unfinished.isEmpty()) {
            return;
        }
        log.info("启动恢复：发现 {} 个未完成任务，重置为 QUEUED 重新入队", unfinished.size());
        taskRepository.updateStatusIn(List.of(TaskStatus.QUEUED, TaskStatus.PROCESSING),
                TaskStatus.QUEUED);
        for (VectorTaskEntity task : unfinished) {
            submitWithRetry(task);
        }
    }

    /** 恢复阶段队列不应满；防御性重试直至入队成功 */
    private void submitWithRetry(VectorTaskEntity task) {
        while (true) {
            try {
                submit(task);
                return;
            } catch (RejectedExecutionException e) {
                log.warn("恢复入队被拒（队列已满），200ms 后重试，taskId={}", task.getTaskId());
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("向量化引擎关闭中，等待任务收尾…");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
