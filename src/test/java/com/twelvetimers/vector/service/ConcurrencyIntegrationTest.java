package com.twelvetimers.vector.service;

import com.twelvetimers.vector.dto.SearchRequest;
import com.twelvetimers.vector.dto.SubmitDocumentRequest;
import com.twelvetimers.vector.dto.SubmitDocumentResponse;
import com.twelvetimers.vector.entity.DocumentStatus;
import com.twelvetimers.vector.exception.BusinessException;
import com.twelvetimers.vector.exception.ErrorCode;
import com.twelvetimers.vector.index.ReadyIndexService;
import com.twelvetimers.vector.repository.DocumentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 并发安全集成测试（考察点：多线程并发安全）。
 *
 * <p>覆盖：并发提交、并发重复提交（唯一约束 + 业务查重）、
 * 并发检索命中计数原子性、检索与失效并发、索引并发写不丢失更新。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:vectordb_concurrency;DB_CLOSE_DELAY=-1",
        "vector.embedding.simulated-delay-ms=0",
        "vector.embedding.busy-rounds=1000"
})
class ConcurrencyIntegrationTest {

    private static final int THREADS = 16;

    @Autowired
    private DocumentService documentService;
    @Autowired
    private SearchService searchService;
    @Autowired
    private DocumentRepository documentRepository;
    @Autowired
    private ReadyIndexService readyIndex;

    @Test
    @DisplayName("并发提交 32 篇文档：全部最终就绪，索引无丢失更新")
    void concurrentSubmitAllBecomeReadyAndIndexed() throws Exception {
        int count = 32;
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SubmitDocumentResponse>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final String docId = "cc-submit-" + i;
            futures.add(pool.submit(() -> {
                start.await();
                return documentService.submit(
                        new SubmitDocumentRequest(docId, "并发提交验证文本-" + docId, null));
            }));
        }
        start.countDown();
        for (Future<SubmitDocumentResponse> future : futures) {
            future.get(10, TimeUnit.SECONDS); // 任一提交抛异常 → 测试失败
        }

        await(() -> documentRepository.findByStatusIn(List.of(DocumentStatus.READY)).size()
                >= count);
        for (int i = 0; i < count; i++) {
            String docId = "cc-submit-" + i;
            assertTrue(readyIndex.snapshot().containsKey(docId),
                    "索引应包含 " + docId + "（并发写不允许丢失更新）");
            assertEquals(DocumentStatus.READY,
                    documentRepository.findByDocId(docId).orElseThrow().getStatus());
        }
        pool.shutdownNow();
    }

    @Test
    @DisplayName("并发提交同一 docId：恰好一个成功，其余 DOC_DUPLICATE")
    void concurrentDuplicateSubmitOnlyOneSucceeds() throws Exception {
        int attempts = 16;
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger duplicate = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            futures.add(pool.submit(() -> {
                awaitLatch(start);
                try {
                    documentService.submit(
                            new SubmitDocumentRequest("cc-dup-doc", "并发重复提交文本", null));
                    success.incrementAndGet();
                } catch (BusinessException e) {
                    if (e.getErrorCode() == ErrorCode.DOC_DUPLICATE) {
                        duplicate.incrementAndGet();
                    } else {
                        throw new AssertionError("非预期的业务异常: " + e.getErrorCode(), e);
                    }
                }
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        assertEquals(1, success.get(), "应恰好一个提交成功");
        assertEquals(attempts - 1, duplicate.get(), "其余提交应全部 DOC_DUPLICATE");
        pool.shutdownNow();
    }

    @Test
    @DisplayName("并发检索：命中计数原子累加不丢失（总命中 = 检索次数 × topK）")
    void concurrentSearchHitCountsAreExact() throws Exception {
        int docCount = 5;
        for (int i = 0; i < docCount; i++) {
            documentService.submit(new SubmitDocumentRequest(
                    "cc-hit-" + i, "命中计数验证文本-" + i, null));
        }
        await(() -> documentRepository.findByStatusIn(List.of(DocumentStatus.READY)).size()
                >= docCount);

        int searches = 40;
        int topK = 3;
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < searches; i++) {
            futures.add(pool.submit(() -> {
                awaitLatch(start);
                assertEquals(topK, searchService.search(
                        new SearchRequest("命中计数验证文本-0", topK, null)).items().size());
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }

        long totalHits = documentRepository.findByStatusIn(List.of(DocumentStatus.READY)).stream()
                .mapToLong(d -> d.getHitCount())
                .sum();
        assertEquals((long) searches * topK, totalHits,
                "并发累加后总命中数应精确等于检索次数 × topK，不允许丢失计数");
        pool.shutdownNow();
    }

    @Test
    @DisplayName("检索与失效并发：无异常，失效后文档不再返回")
    void concurrentSearchAndInvalidateIsSafe() throws Exception {
        int docCount = 8;
        for (int i = 0; i < docCount; i++) {
            documentService.submit(new SubmitDocumentRequest(
                    "cc-inv-" + i, "检索失效并发验证文本-" + i, null));
        }
        await(() -> documentRepository.findByStatusIn(List.of(DocumentStatus.READY)).size()
                >= docCount);

        String target = "cc-inv-0";
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        futures.add(pool.submit(() -> {
            awaitLatch(start);
            documentService.invalidate(target);
        }));
        for (int i = 0; i < THREADS - 1; i++) {
            futures.add(pool.submit(() -> {
                awaitLatch(start);
                for (int round = 0; round < 30; round++) {
                    searchService.search(new SearchRequest("检索失效并发验证文本-1", 5, null));
                }
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(20, TimeUnit.SECONDS); // 任一异常 → 测试失败
        }

        // 最终一致性：失效文档不在索引、不再返回、状态 INVALID
        assertFalse(readyIndex.snapshot().containsKey(target), "失效文档应从索引移除");
        assertEquals(DocumentStatus.INVALID,
                documentRepository.findByDocId(target).orElseThrow().getStatus());
        var result = searchService.search(new SearchRequest("检索失效并发验证文本-1", 10, null));
        assertTrue(result.items().stream().noneMatch(item -> target.equals(item.docId())),
                "失效文档不应再出现在检索结果");
        pool.shutdownNow();
    }

    /** 等待发令枪；中断则转为运行时异常（测试线程不允许被中断静默吞掉） */
    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    /** 轮询等待异步条件成立（上限 15 秒） */
    private static void await(Supplier<Boolean> condition) {
        long deadline = System.currentTimeMillis() + 15_000;
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
        if (!condition.get()) {
            fail("15 秒内条件未满足");
        }
    }
}
