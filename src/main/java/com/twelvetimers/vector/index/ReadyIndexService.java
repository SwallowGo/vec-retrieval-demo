package com.twelvetimers.vector.index;

import com.google.common.collect.ImmutableMap;
import com.twelvetimers.vector.entity.DocumentEntity;
import com.twelvetimers.vector.entity.DocumentStatus;
import com.twelvetimers.vector.repository.DocumentRepository;
import com.twelvetimers.vector.service.embedding.EmbeddingCompletionListener;
import com.twelvetimers.vector.service.embedding.VectorCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 内存线程安全检索索引：只包含「已就绪且未失效」文档的向量。
 *
 * <p>并发模型（考察点：内存线程安全存储）：
 * <ul>
 *   <li><b>读</b>（检索高频）：{@link #snapshot()} 做一次 volatile 读拿到不可变快照，
 *       之后遍历期间零锁，且不受并发写影响；</li>
 *   <li><b>写</b>（向量化完成 / 失效，低频）：copy-on-write —— 基于当前快照构建
 *       新 {@link ImmutableMap} 后 volatile 发布；写路径 synchronized 串行化，
 *       避免多个并发写（如多工作线程同时完成）之间的丢失更新。</li>
 * </ul>
 *
 * <p>一致性约定：先 DB 事务提交、后更新索引 —— 索引里有的数据 DB 里一定有；
 * 应用重启时从 DB 重建，与任务恢复重放的回调共用同一把写锁，任何交错都不丢条目。
 */
@Component
@Slf4j
public class ReadyIndexService implements EmbeddingCompletionListener {

    /** 检索条目：渠道 + 256 维向量（调用方按只读使用） */
    public record IndexedVector(String channel, float[] vector) {
    }

    private final DocumentRepository documentRepository;

    /** 不可变快照；写时整体替换并 volatile 发布 */
    private volatile ImmutableMap<String, IndexedVector> snapshot = ImmutableMap.of();

    public ReadyIndexService(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    /** 取只读快照（volatile 读）。返回后遍历不受并发写影响。 */
    public Map<String, IndexedVector> snapshot() {
        return snapshot;
    }

    @Override
    public void onEmbedded(String docId, String channel, float[] vector) {
        put(docId, channel, vector);
    }

    public synchronized void put(String docId, String channel, float[] vector) {
        snapshot = ImmutableMap.<String, IndexedVector>builder()
                .putAll(snapshot)
                .put(docId, new IndexedVector(channel, vector))
                .build();
    }

    public synchronized void remove(String docId) {
        ImmutableMap.Builder<String, IndexedVector> builder = ImmutableMap.builder();
        snapshot.forEach((key, value) -> {
            if (!key.equals(docId)) {
                builder.put(key, value);
            }
        });
        snapshot = builder.build();
    }

    /** 启动重建：合并式写入（与重放回调共用写锁，条目不丢失） */
    @EventListener(ApplicationReadyEvent.class)
    public void rebuildFromDatabase() {
        List<DocumentEntity> readyDocs =
                documentRepository.findByStatusIn(List.of(DocumentStatus.READY));
        if (readyDocs.isEmpty()) {
            return;
        }
        synchronized (this) {
            ImmutableMap.Builder<String, IndexedVector> builder = ImmutableMap.builder();
            builder.putAll(snapshot);
            for (DocumentEntity document : readyDocs) {
                float[] vector = VectorCodec.decode(document.getVector());
                if (vector != null) {
                    builder.put(document.getDocId(),
                            new IndexedVector(document.getChannel(), vector));
                }
            }
            snapshot = builder.build();
        }
        log.info("内存索引启动重建完成，就绪文档 {} 篇", snapshot.size());
    }
}
