package com.twelvetimers.vector.service.embedding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Random;

/**
 * 模拟 Embedding 向量化算法。
 *
 * <p>真实 Embedding 的特性：相同文本输出向量稳定。本实现用「文本哈希派生向量」模拟：
 *
 * <ol>
 *   <li>SHA-256 对文本取摘要，前 8 字节作为随机种子；
 *   <li>{@link Random#Random(long)} 同种子产生的序列由 JLS 保证完全确定，
 *       据此生成 256 维、值域 [-1, 1] 的向量；
 *   <li>L2 归一化为单位向量 —— 此时余弦相似度退化为点积，检索计算简化。
 * </ol>
 *
 * <p>空文本/纯空白文本输出零向量（与任何向量相似度均为 0，检索时永远垫底）。
 * 算法无时间、无全局状态依赖，同一文本在任何时刻、任何线程输出的向量完全一致。
 */
public final class VectorCalculator {

    /** 向量固定维度 */
    public static final int DIMENSIONS = 256;

    private static final String SHA_256 = "SHA-256";

    private VectorCalculator() {
    }

    /**
     * 文本 → 256 维 float 单位向量。同文本永远返回完全一致的向量。
     *
     * @param text 原始文本；null 或空白文本返回零向量
     */
    public static float[] vectorize(String text) {
        if (text == null || text.isBlank()) {
            return new float[DIMENSIONS]; // 零向量
        }
        Random random = new Random(sha256Seed(text));
        float[] vector = new float[DIMENSIONS];
        double norm = 0.0;
        for (int i = 0; i < DIMENSIONS; i++) {
            vector[i] = random.nextFloat() * 2.0f - 1.0f; // 值域 [-1, 1]
            norm += (double) vector[i] * vector[i];
        }
        if (norm > 0) {
            float length = (float) Math.sqrt(norm);
            for (int i = 0; i < DIMENSIONS; i++) {
                vector[i] /= length;
            }
        }
        return vector;
    }

    /**
     * 余弦相似度。因向量已 L2 归一化，等价于点积，结果落在 [-1, 1]；
     * 零向量与任何向量的相似度均为 0。
     *
     * @throws IllegalArgumentException 维度不一致
     */
    public static float cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("向量不能为 null");
        }
        if (a.length != b.length) {
            throw new IllegalArgumentException("向量维度不一致: " + a.length + " vs " + b.length);
        }
        float dot = 0.0f;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }

    /** SHA-256 摘要的前 8 字节转 long，作为确定性随机种子 */
    private static long sha256Seed(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256);
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            long seed = 0L;
            for (int i = 0; i < Long.BYTES; i++) {
                seed = (seed << 8) | (hash[i] & 0xFFL);
            }
            return seed;
        } catch (NoSuchAlgorithmException e) {
            // JDK 强制要求实现 SHA-256，正常不会发生
            throw new IllegalStateException("SHA-256 摘要算法不可用", e);
        }
    }
}
