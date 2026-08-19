package com.twelvetimers.vector.service.embedding;

/**
 * 向量化完成回调（事务提交后触发）。
 *
 * <p>用于将完成事件发布给内存索引等关注方；引擎与索引解耦，
 * 索引实现类注册为 Spring Bean 即可自动生效。
 */
public interface EmbeddingCompletionListener {

    /**
     * 文档向量化成功且事务已提交。
     *
     * @param docId   文档 ID
     * @param channel 渠道
     * @param vector  256 维向量（不可变使用：调用方不得修改）
     */
    void onEmbedded(String docId, String channel, float[] vector);
}
