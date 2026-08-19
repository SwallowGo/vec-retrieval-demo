package com.twelvetimers.vector.service.embedding;

import com.twelvetimers.vector.entity.VectorTaskEntity;

/**
 * 工作线程消费单元：从阻塞队列取出后交给 {@link EmbeddingProcessor} 执行。
 *
 * <p>任务实体的 id/taskId/docId 在入队前已持久化，这里仅做字段快照，
 * 供工作线程读取（无懒加载关联，跨线程使用安全）。
 */
public class EmbeddingWorker implements Runnable {

    private final VectorTaskEntity task;
    private final EmbeddingProcessor processor;

    public EmbeddingWorker(VectorTaskEntity task, EmbeddingProcessor processor) {
        this.task = task;
        this.processor = processor;
    }

    @Override
    public void run() {
        processor.process(task);
    }
}
