package com.twelvetimers.vector.service;

import com.twelvetimers.vector.dto.DocumentDetailResponse;
import com.twelvetimers.vector.dto.DocumentInfo;
import com.twelvetimers.vector.dto.DocumentListResponse;
import com.twelvetimers.vector.dto.SubmitDocumentRequest;
import com.twelvetimers.vector.dto.SubmitDocumentResponse;
import com.twelvetimers.vector.dto.TaskBrief;
import com.twelvetimers.vector.entity.DocumentEntity;
import com.twelvetimers.vector.entity.DocumentStatus;
import com.twelvetimers.vector.entity.TaskStatus;
import com.twelvetimers.vector.entity.VectorTaskEntity;
import com.twelvetimers.vector.exception.BusinessException;
import com.twelvetimers.vector.exception.ErrorCode;
import com.twelvetimers.vector.index.ReadyIndexService;
import com.twelvetimers.vector.repository.DocumentRepository;
import com.twelvetimers.vector.repository.VectorTaskRepository;
import com.twelvetimers.vector.service.embedding.EmbeddingEngine;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

/**
 * 文档管理：入库（异步提交）、失效标记、列表与详情。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentService {

    private static final String DEFAULT_CHANNEL = "default";

    private final DocumentRepository documentRepository;
    private final VectorTaskRepository taskRepository;
    private final EmbeddingEngine embeddingEngine;
    private final ReadyIndexService readyIndex;
    private final TransactionTemplate transactionTemplate;

    /**
     * 文档入库：持久化 PENDING 文档 + QUEUED 任务后投递异步队列，立即返回任务 ID。
     * 注意：方法不加 @Transactional —— 入库与入队之间的失败处理依赖各仓库调用
     * 自身的原子事务，队满时任务/文档需落库 FAILED 后向客户端报错（若整段回滚，
     * 会留下「任务 QUEUED 却从未执行」的假象）。
     */
    public SubmitDocumentResponse submit(SubmitDocumentRequest request) {
        String channel = StringUtils.defaultIfBlank(request.channel(), DEFAULT_CHANNEL);

        // 查重（并发重复提交由唯一约束兜底，见下方 catch）
        if (documentRepository.existsByDocId(request.docId())) {
            throw new BusinessException(ErrorCode.DOC_DUPLICATE,
                    "文档已存在: " + request.docId());
        }

        DocumentEntity document = new DocumentEntity();
        document.setDocId(request.docId());
        document.setText(request.text());
        document.setChannel(channel);
        document.setStatus(DocumentStatus.PENDING);
        document.setSubmitTime(LocalDateTime.now());
        try {
            documentRepository.save(document);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.DOC_DUPLICATE,
                    "文档已存在: " + request.docId());
        }

        VectorTaskEntity task = new VectorTaskEntity();
        task.setTaskId(UUID.randomUUID().toString());
        task.setDocId(request.docId());
        task.setStatus(TaskStatus.QUEUED);
        task.setCreateTime(LocalDateTime.now());
        taskRepository.save(task);

        try {
            embeddingEngine.submit(task);
        } catch (RejectedExecutionException e) {
            // 背压：队满拒绝 → 落库失败状态后返回业务异常，不假排队
            LocalDateTime now = LocalDateTime.now();
            task.setStatus(TaskStatus.FAILED);
            task.setErrorMsg("向量化队列已满，请稍后重试");
            task.setFinishTime(now);
            document.setStatus(DocumentStatus.FAILED);
            taskRepository.save(task);
            documentRepository.save(document);
            log.warn("队列已满，任务 {} 标记失败", task.getTaskId());
            throw new BusinessException(ErrorCode.QUEUE_FULL);
        }
        log.info("文档 {} 已提交，任务 {}", request.docId(), task.getTaskId());
        return new SubmitDocumentResponse(task.getTaskId());
    }

    /**
     * 标记文档失效：失效文档从内存索引移除，不再参与检索。
     * 已失效 → 幂等成功；FAILED → 业务异常；PENDING/READY → 条件更新防并发竞态。
     */
    public void invalidate(String docId) {
        DocumentEntity document = documentRepository.findByDocId(docId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOC_NOT_FOUND,
                        "文档不存在: " + docId));
        if (document.getStatus() == DocumentStatus.INVALID) {
            return; // 幂等
        }
        if (document.getStatus() == DocumentStatus.FAILED) {
            throw new BusinessException(ErrorCode.DOC_STATUS_NOT_ALLOWED,
                    "文档向量化失败，无法标记失效: " + docId);
        }
        // 事务内条件更新；提交后再从索引移除（索引更新永远晚于 DB 提交）
        int rows = transactionTemplate.execute(status -> documentRepository.markInvalidIfStatus(
                docId,
                List.of(DocumentStatus.PENDING, DocumentStatus.READY),
                DocumentStatus.INVALID, LocalDateTime.now()));
        if (rows == 0) {
            // 并发窗口内状态已变化：重读确认
            DocumentStatus latest = documentRepository.findByDocId(docId)
                    .map(DocumentEntity::getStatus).orElse(DocumentStatus.FAILED);
            if (latest == DocumentStatus.INVALID) {
                return; // 已被其他请求失效，幂等
            }
            throw new BusinessException(ErrorCode.DOC_STATUS_NOT_ALLOWED,
                    "文档当前状态不允许标记失效: " + latest);
        }
        readyIndex.remove(docId);
        log.info("文档 {} 已标记失效", docId);
    }

    /** 文档列表：分页 + 渠道/状态过滤，按入库倒序 */
    public DocumentListResponse list(int page, int size, String channel, String status) {
        if (page < 1) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "page 必须 ≥ 1");
        }
        if (size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "size 取值范围 [1, 100]");
        }
        DocumentStatus statusEnum = parseStatus(status);
        Page<DocumentEntity> result = documentRepository.findAll(
                buildSpec(channel, statusEnum),
                PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "id")));
        return new DocumentListResponse(
                result.getContent().stream().map(DocumentInfo::from).toList(),
                page, size, result.getTotalElements());
    }

    /** 文档详情：元信息 + 关联任务 */
    public DocumentDetailResponse detail(String docId) {
        DocumentEntity document = documentRepository.findByDocId(docId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOC_NOT_FOUND,
                        "文档不存在: " + docId));
        TaskBrief task = taskRepository.findByDocId(docId).map(TaskBrief::from).orElse(null);
        return new DocumentDetailResponse(DocumentInfo.from(document), task);
    }

    private Specification<DocumentEntity> buildSpec(String channel, DocumentStatus status) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (StringUtils.isNotBlank(channel)) {
                predicates.add(cb.equal(root.get("channel"), channel));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private DocumentStatus parseStatus(String status) {
        if (StringUtils.isBlank(status)) {
            return null;
        }
        try {
            return DocumentStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "非法文档状态: " + status);
        }
    }
}
