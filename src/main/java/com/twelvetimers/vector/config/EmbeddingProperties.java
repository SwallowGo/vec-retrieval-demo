package com.twelvetimers.vector.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 向量化异步引擎配置（application.yml 中 vector.embedding.* 可调）。
 *
 * @param workers           工作线程数（CPU 密集任务，默认 4）
 * @param queueCapacity     有界阻塞队列容量（背压：队满即拒绝）
 * @param busyRounds        模拟 CPU 耗时的哈希迭代轮数
 * @param simulatedDelayMs  模拟外部 Embedding API 往返延迟（毫秒）
 */
@ConfigurationProperties(prefix = "vector.embedding")
public record EmbeddingProperties(
        @DefaultValue("4") int workers,
        @DefaultValue("1024") int queueCapacity,
        @DefaultValue("20000") long busyRounds,
        @DefaultValue("100") long simulatedDelayMs) {
}
