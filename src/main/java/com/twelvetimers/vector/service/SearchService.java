package com.twelvetimers.vector.service;

import com.twelvetimers.vector.dto.SearchHitItem;
import com.twelvetimers.vector.dto.SearchRequest;
import com.twelvetimers.vector.dto.SearchResponse;
import com.twelvetimers.vector.exception.BusinessException;
import com.twelvetimers.vector.exception.ErrorCode;
import com.twelvetimers.vector.index.ReadyIndexService;
import com.twelvetimers.vector.repository.DocumentRepository;
import com.twelvetimers.vector.service.embedding.VectorCalculator;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * 向量检索（同步接口）：
 *
 * <ol>
 *   <li>查询文本执行与入库一致的向量化逻辑；</li>
 *   <li>在内存索引快照中计算余弦相似度（小顶堆维护 Top-K，O(N log K)）；</li>
 *   <li>命中计数由数据库原子累加（绕过应用层读-改-写）。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class SearchService {

    public static final int DEFAULT_TOP_K = 10;
    public static final int MAX_TOP_K = 100;

    private final ReadyIndexService readyIndex;
    private final DocumentRepository documentRepository;

    @Transactional
    public SearchResponse search(SearchRequest request) {
        int topK = request.topK() == null ? DEFAULT_TOP_K : request.topK();
        if (topK < 1 || topK > MAX_TOP_K) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                    "topK 取值范围 [1, " + MAX_TOP_K + "]");
        }
        String channel = StringUtils.trimToNull(request.channel());

        // ① 查询文本向量化（与入库同一算法，保证可比性）
        float[] queryVector = VectorCalculator.vectorize(request.text());

        // ② 遍历快照（volatile 读一次），小顶堆维护 Top-K
        PriorityQueue<Hit> heap = new PriorityQueue<>(Comparator.comparingDouble(Hit::score));
        for (Map.Entry<String, ReadyIndexService.IndexedVector> entry
                : readyIndex.snapshot().entrySet()) {
            ReadyIndexService.IndexedVector candidate = entry.getValue();
            if (channel != null && !channel.equals(candidate.channel())) {
                continue;
            }
            float score = VectorCalculator.cosineSimilarity(queryVector, candidate.vector());
            if (heap.size() < topK) {
                heap.offer(new Hit(entry.getKey(), candidate.channel(), score));
            } else if (score > heap.peek().score()) {
                heap.poll();
                heap.offer(new Hit(entry.getKey(), candidate.channel(), score));
            }
        }

        // ③ 相似度降序（同分按 docId 稳定排序）
        List<Hit> hits = new ArrayList<>(heap);
        hits.sort(Comparator.comparingDouble(Hit::score).reversed()
                .thenComparing(Hit::docId));

        // ④ 命中计数原子累加（仅本次实际返回的 Top-K 文档）
        if (!hits.isEmpty()) {
            documentRepository.incrementHitCountByIds(
                    hits.stream().map(Hit::docId).toList());
        }
        return new SearchResponse(hits.stream()
                .map(hit -> new SearchHitItem(hit.docId(), hit.channel(),
                        Math.round(hit.score() * 10000) / 10000.0))
                .toList());
    }

    private record Hit(String docId, String channel, double score) {
    }
}
