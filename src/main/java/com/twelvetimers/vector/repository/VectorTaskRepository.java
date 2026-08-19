package com.twelvetimers.vector.repository;

import com.twelvetimers.vector.entity.TaskStatus;
import com.twelvetimers.vector.entity.VectorTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface VectorTaskRepository extends JpaRepository<VectorTaskEntity, Long> {

    Optional<VectorTaskEntity> findByTaskId(String taskId);

    List<VectorTaskEntity> findByStatusIn(Collection<TaskStatus> statuses);

    /**
     * 状态机条件更新（乐观锁）：仅当任务仍处于 expected 状态时才流转到 to 状态。
     * 影响行数为 0 说明已被其他线程抢占 —— 防止任务被重复消费。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update VectorTaskEntity t set t.status = :to, t.startTime = :startTime "
            + "where t.id = :id and t.status = :expected")
    int claimIfStatus(@Param("id") Long id,
                      @Param("expected") TaskStatus expected,
                      @Param("to") TaskStatus to,
                      @Param("startTime") LocalDateTime startTime);

    /** 批量重置状态（启动恢复：遗留的 QUEUED/PROCESSING 统一重置为 QUEUED 重放） */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update VectorTaskEntity t set t.status = :to where t.status in :from")
    int updateStatusIn(@Param("from") Collection<TaskStatus> from, @Param("to") TaskStatus to);
}
