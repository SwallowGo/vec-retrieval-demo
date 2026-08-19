package com.twelvetimers.vector.service;

import com.twelvetimers.vector.dto.DocumentDetailResponse;
import com.twelvetimers.vector.dto.DocumentInfo;
import com.twelvetimers.vector.dto.SearchHitItem;
import com.twelvetimers.vector.dto.SearchRequest;
import com.twelvetimers.vector.dto.SearchResponse;
import com.twelvetimers.vector.dto.SubmitDocumentRequest;
import com.twelvetimers.vector.dto.SubmitDocumentResponse;
import com.twelvetimers.vector.dto.TaskInfoResponse;
import com.twelvetimers.vector.entity.DocumentStatus;
import com.twelvetimers.vector.exception.BusinessException;
import com.twelvetimers.vector.exception.ErrorCode;
import com.twelvetimers.vector.repository.DocumentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 入库 → 异步向量化 → 检索 → 失效 全流程集成测试。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:vectordb_flow;DB_CLOSE_DELAY=-1",
        "vector.embedding.simulated-delay-ms=0",
        "vector.embedding.busy-rounds=1000"
})
class SearchAndDocumentFlowTest {

    @Autowired
    private DocumentService documentService;
    @Autowired
    private SearchService searchService;
    @Autowired
    private TaskService taskService;
    @Autowired
    private DocumentRepository documentRepository;

    @Test
    @DisplayName("全流程：入库异步完成、相同文本检索得分最高、命中计数累加")
    void fullFlowSearchRanksAndCountsHits() {
        SubmitDocumentResponse r1 = submit("flow-doc-1", "Java 并发编程线程池阻塞队列");
        SubmitDocumentResponse r2 = submit("flow-doc-2", "Java 并发编程线程池阻塞队列");
        SubmitDocumentResponse r3 = submit("flow-doc-3", "今天天气不错适合出去玩");
        awaitReady("flow-doc-1", "flow-doc-2", "flow-doc-3");

        // 任务状态查询：SUCCESS 且携带文档信息
        TaskInfoResponse taskInfo = taskService.getTask(r1.taskId());
        assertEquals("SUCCESS", taskInfo.status());
        assertNotNull(taskInfo.document());
        assertEquals("flow-doc-1", taskInfo.document().docId());

        // 相同文本 → 相似度 1.0，排在首位；topK=2 时无关文档不返回
        SearchResponse search = searchService.search(
                new SearchRequest("Java 并发编程线程池阻塞队列", 2, null));
        assertEquals(2, search.items().size());
        assertEquals(1.0, search.items().get(0).score(), 1e-4);
        assertTrue(search.items().get(0).docId().startsWith("flow-doc-1")
                        || search.items().get(0).docId().startsWith("flow-doc-2"),
                "相同文本的文档应并列最高分");
        assertEquals(1.0, search.items().get(1).score(), 1e-4);

        // 命中计数：被返回的 Top-K 文档计数 +1
        for (SearchHitItem item : search.items()) {
            assertEquals(1L, hitCount(item.docId()), item.docId() + " 命中计数应为 1");
        }
        assertEquals(0L, hitCount("flow-doc-3"), "未命中文档计数应为 0");
    }

    @Test
    @DisplayName("渠道过滤：仅返回指定渠道文档")
    void channelFilterOnlyReturnsMatchingChannel() {
        submit("flow-chan-1", "渠道过滤测试文本内容", "news");
        submit("flow-chan-2", "渠道过滤测试文本内容", "blog");
        submit("flow-chan-3", "渠道过滤测试文本内容", "news");
        awaitReady("flow-chan-1", "flow-chan-2", "flow-chan-3");

        SearchResponse filtered = searchService.search(
                new SearchRequest("渠道过滤测试文本内容", 10, "news"));
        assertEquals(2, filtered.items().size());
        assertTrue(filtered.items().stream().allMatch(item -> "news".equals(item.channel())));
    }

    @Test
    @DisplayName("失效文档不再参与检索，且失效幂等")
    void invalidatedDocumentIsExcludedFromSearch() {
        submit("flow-invalid-1", "失效文档验证文本");
        submit("flow-invalid-2", "失效文档验证文本");
        awaitReady("flow-invalid-1", "flow-invalid-2");

        documentService.invalidate("flow-invalid-1");
        documentService.invalidate("flow-invalid-1"); // 幂等

        SearchResponse search = searchService.search(
                new SearchRequest("失效文档验证文本", 10, null));
        assertTrue(search.items().stream()
                        .noneMatch(item -> "flow-invalid-1".equals(item.docId())),
                "失效文档不应出现在检索结果中");

        DocumentDetailResponse detail = documentService.detail("flow-invalid-1");
        assertEquals("INVALID", detail.document().status());
        assertNotNull(detail.document().invalidTime());
    }

    @Test
    @DisplayName("重复提交同一 docId 被拒绝（409）")
    void duplicateSubmitIsRejected() {
        submit("flow-dup-1", "重复提交验证文本");
        BusinessException e = assertThrows(BusinessException.class,
                () -> submit("flow-dup-1", "重复提交验证文本"));
        assertEquals(ErrorCode.DOC_DUPLICATE, e.getErrorCode());
    }

    @Test
    @DisplayName("文档详情与列表：元信息、渠道默认值、状态过滤")
    void documentDetailAndList() {
        submit("flow-list-1", "列表验证文本一", "shop");
        submit("flow-list-2", "列表验证文本二");
        awaitReady("flow-list-1", "flow-list-2");

        DocumentDetailResponse detail = documentService.detail("flow-list-2");
        assertEquals("default", detail.document().channel(), "渠道不传默认 default");
        assertTrue(detail.document().vectorReady());
        assertNotNull(detail.task());

        var list = documentService.list(1, 10, "shop", "READY");
        assertEquals(1, list.total());
        assertEquals("flow-list-1", list.items().get(0).docId());
    }

    @Test
    @DisplayName("topK 越界与任务不存在返回业务异常")
    void invalidParamsThrowBusinessException() {
        BusinessException topK = assertThrows(BusinessException.class,
                () -> searchService.search(new SearchRequest("任意文本", 0, null)));
        assertEquals(ErrorCode.INVALID_PARAM, topK.getErrorCode());

        BusinessException task = assertThrows(BusinessException.class,
                () -> taskService.getTask("task-not-exist"));
        assertEquals(ErrorCode.TASK_NOT_FOUND, task.getErrorCode());

        BusinessException doc = assertThrows(BusinessException.class,
                () -> documentService.detail("doc-not-exist"));
        assertEquals(ErrorCode.DOC_NOT_FOUND, doc.getErrorCode());
    }

    private SubmitDocumentResponse submit(String docId, String text) {
        return documentService.submit(new SubmitDocumentRequest(docId, text, null));
    }

    private SubmitDocumentResponse submit(String docId, String text, String channel) {
        return documentService.submit(new SubmitDocumentRequest(docId, text, channel));
    }

    private long hitCount(String docId) {
        return documentRepository.findByDocId(docId).map(d -> d.getHitCount()).orElse(-1L);
    }

    private void awaitReady(String... docIds) {
        await(() -> {
            for (String docId : docIds) {
                DocumentInfo info = documentService.detail(docId).document();
                if (!info.vectorReady()) {
                    return false;
                }
            }
            return true;
        });
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
