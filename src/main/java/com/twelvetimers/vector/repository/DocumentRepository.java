package com.twelvetimers.vector.repository;

import com.twelvetimers.vector.entity.DocumentEntity;
import com.twelvetimers.vector.entity.DocumentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<DocumentEntity, Long>,
        JpaSpecificationExecutor<DocumentEntity> {

    Optional<DocumentEntity> findByDocId(String docId);

    boolean existsByDocId(String docId);

    List<DocumentEntity> findByStatusIn(Collection<DocumentStatus> statuses);

    /**
     * 命中计数原子累加：绕过应用层读-改-写，直接由数据库原子执行，
     * 多线程并发检索不会丢失计数。
     */
    @Modifying(clearAutomatically = true)
    @Query("update DocumentEntity d set d.hitCount = d.hitCount + 1 where d.docId in :docIds")
    int incrementHitCountByIds(@Param("docIds") Collection<String> docIds);

    /**
     * 向量化完成的条件更新：仅当文档仍为 expected 状态时才写入向量并置 to。
     * 处理期间被标记失效的文档不会被重新置为 READY。
     */
    @Modifying(clearAutomatically = true)
    @Query("update DocumentEntity d set d.status = :to, d.vector = :vector, "
            + "d.completeTime = :time where d.id = :id and d.status = :expected")
    int markReadyIfStatus(@Param("id") Long id,
                          @Param("expected") DocumentStatus expected,
                          @Param("to") DocumentStatus to,
                          @Param("vector") byte[] vector,
                          @Param("time") LocalDateTime time);

    /**
     * 标记失效的条件更新：仅 PENDING/READY 可失效，原子排除并发竞态。
     */
    @Modifying(clearAutomatically = true)
    @Query("update DocumentEntity d set d.status = :to, d.invalidTime = :time "
            + "where d.docId = :docId and d.status in :from")
    int markInvalidIfStatus(@Param("docId") String docId,
                            @Param("from") Collection<DocumentStatus> from,
                            @Param("to") DocumentStatus to,
                            @Param("time") LocalDateTime time);
}
