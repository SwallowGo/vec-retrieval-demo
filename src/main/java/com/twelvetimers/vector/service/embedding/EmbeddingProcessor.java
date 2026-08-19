package com.twelvetimers.vector.service.embedding;

import com.twelvetimers.vector.config.EmbeddingProperties;
import com.twelvetimers.vector.entity.DocumentEntity;
import com.twelvetimers.vector.entity.DocumentStatus;
import com.twelvetimers.vector.entity.TaskStatus;
import com.twelvetimers.vector.entity.VectorTaskEntity;
import com.twelvetimers.vector.repository.DocumentRepository;
import com.twelvetimers.vector.repository.VectorTaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 单个向量化任务的执行逻辑（由工作线程调用）。
 *
 * <p>事务边界刻意拆成两段：抢占状态（短事务）→ CPU 耗时计算（无事务，
 * 不占用数据库连接，这也是任务必须异步执行的原因）→ 结果落库（短事务）。
 */
@Service
@Slf4j
public class EmbeddingProcessor {

    private final DocumentRepository documentRepository;
    private final VectorTaskRepository taskRepository;
    private final TransactionTemplate transactionTemplate;
    private final EmbeddingProperties properties;
    private final List<EmbeddingCompletionListener> completionListeners;

    public EmbeddingProcessor(DocumentRepository documentRepository,
                              VectorTaskRepository taskRepository,
                              TransactionTemplate transactionTemplate,
                              EmbeddingProperties properties,
                              List<EmbeddingCompletionListener> completionListeners) {
        this.documentRepository = documentRepository;
        this.taskRepository = taskRepository;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
        this.completionListeners = completionListeners;
    }

    /** 任务主流程：QUEUED → PROCESSING → SUCCESS / FAILED */
    public void process(VectorTaskEntity task) {
        log.info("任务 {} 开始处理，文档 {}", task.getTaskId(), task.getDocId());
        try {
            // 状态机：条件更新抢占 PROCESSING，防重复消费
            boolean claimed = Boolean.TRUE.equals(transactionTemplate.execute(status ->
                    taskRepository.claimIfStatus(task.getId(), TaskStatus.QUEUED,
                            TaskStatus.PROCESSING, LocalDateTime.now()) > 0));
            if (!claimed) {
                log.warn("任务 {} 已被其他线程抢占，跳过", task.getTaskId());
                return;
            }

            DocumentEntity document = documentRepository.findByDocId(task.getDocId())
                    .orElseThrow(() -> new IllegalStateException("文档不存在: " + task.getDocId()));

            // CPU 耗时模拟 + 向量化（无事务，不占数据库连接）
            float[] vector = vectorizeWithSimulatedCost(document.getText());

            complete(task.getId(), document.getId(), document.getDocId(),
                    document.getChannel(), vector);
            log.info("任务 {} 完成，文档 {} 已就绪", task.getTaskId(), task.getDocId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(task, "任务被中断");
            log.warn("任务 {} 被中断", task.getTaskId());
        } catch (Exception e) {
            fail(task, e.getMessage());
            log.error("任务 {} 执行失败", task.getTaskId(), e);
        }
    }

    /** 结果落库（事务）：文档写向量并置 READY，任务置 SUCCESS */
    private void complete(Long taskId, Long documentId, String docId, String channel,
                          float[] vector) {
        transactionTemplate.executeWithoutResult(status -> {
            VectorTaskEntity task = taskRepository.findById(taskId).orElseThrow();
            task.setStatus(TaskStatus.SUCCESS);
            task.setFinishTime(LocalDateTime.now());

            DocumentEntity document = documentRepository.findById(documentId).orElseThrow();
            document.setStatus(DocumentStatus.READY);
            document.setVector(VectorCodec.encode(vector));
            document.setCompleteTime(LocalDateTime.now());
        });
        // 事务已提交后通知（索引更新晚于落库：索引里有的数据 DB 里一定有）
        for (EmbeddingCompletionListener listener : completionListeners) {
            try {
                listener.onEmbedded(docId, channel, vector);
            } catch (Exception e) {
                log.error("完成回调执行失败", e);
            }
        }
    }

    /** 失败落库（事务）：任务 FAILED + 原因，文档同步 FAILED */
    private void fail(VectorTaskEntity task, String errorMessage) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                VectorTaskEntity persisted = taskRepository.findById(task.getId()).orElse(null);
                if (persisted != null) {
                    persisted.setStatus(TaskStatus.FAILED);
                    persisted.setErrorMsg(StringUtils.truncate(errorMessage, 500));
                    persisted.setFinishTime(LocalDateTime.now());
                }
                documentRepository.findByDocId(task.getDocId())
                        .filter(doc -> doc.getStatus() == DocumentStatus.PENDING)
                        .ifPresent(doc -> doc.setStatus(DocumentStatus.FAILED));
            });
        } catch (Exception e) {
            log.error("任务 {} 标记失败时异常", task.getTaskId(), e);
        }
    }

    /**
     * 模拟向量化耗时：迭代哈希忙循环（模拟 CPU 推理开销，结果逐轮参与下一轮，
     * 不会被 JIT 优化消除）+ 小段阻塞（模拟外部 API 往返延迟）。
     */
    private float[] vectorizeWithSimulatedCost(String text) throws InterruptedException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 摘要算法不可用", e);
        }
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        for (long i = 0; i < properties.busyRounds(); i++) {
            data = digest.digest(data);
        }
        Thread.sleep(properties.simulatedDelayMs());
        return VectorCalculator.vectorize(text);
    }
}
