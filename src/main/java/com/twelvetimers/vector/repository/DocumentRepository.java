package com.twelvetimers.vector.repository;

import com.twelvetimers.vector.entity.DocumentEntity;
import com.twelvetimers.vector.entity.DocumentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<DocumentEntity, Long> {

    Optional<DocumentEntity> findByDocId(String docId);

    boolean existsByDocId(String docId);

    List<DocumentEntity> findByStatusIn(Collection<DocumentStatus> statuses);

    /**
     * 命中计数原子累加：绕过应用层读-改-写，直接由数据库原子执行，
     * 多线程并发检索不会丢失计数。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update DocumentEntity d set d.hitCount = d.hitCount + 1 where d.docId in :docIds")
    int incrementHitCountByIds(@Param("docIds") Collection<String> docIds);
}
