package com.twelvetimers.vector.service.embedding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 向量化算法单测：确定性、维度、零向量、归一化、相似度、编解码。
 */
class VectorCalculatorTest {

    @Test
    @DisplayName("同一文本多次向量化结果必须完全一致")
    void sameTextProducesIdenticalVector() {
        String text = "java 并发编程面试题";
        float[] first = VectorCalculator.vectorize(text);
        for (int i = 0; i < 10; i++) {
            assertArrayEquals(first, VectorCalculator.vectorize(text),
                    "第 " + i + " 次向量化结果不一致");
        }
    }

    @Test
    @DisplayName("不同文本产生不同向量")
    void differentTextsProduceDifferentVectors() {
        float[] a = VectorCalculator.vectorize("今天是晴天");
        float[] b = VectorCalculator.vectorize("今天是雨天");
        assertFalse(Arrays.equals(a, b), "不同文本不应产生相同向量");
    }

    @Test
    @DisplayName("空文本/null/纯空白输出 256 维零向量")
    void blankTextProducesZeroVector() {
        float[] expected = new float[VectorCalculator.DIMENSIONS];
        assertArrayEquals(expected, VectorCalculator.vectorize(null));
        assertArrayEquals(expected, VectorCalculator.vectorize(""));
        assertArrayEquals(expected, VectorCalculator.vectorize("   \t\n "));
    }

    @Test
    @DisplayName("非空文本输出固定 256 维向量")
    void vectorHasFixedDimensions() {
        assertEquals(VectorCalculator.DIMENSIONS, VectorCalculator.vectorize("hello").length);
    }

    @Test
    @DisplayName("非空文本向量已 L2 归一化为单位向量")
    void nonBlankVectorIsL2Normalized() {
        float[] vector = VectorCalculator.vectorize("L2 归一化验证文本");
        double norm = 0.0;
        for (float value : vector) {
            norm += (double) value * value;
        }
        assertEquals(1.0, Math.sqrt(norm), 1e-5, "向量范数应接近 1");
    }

    @Test
    @DisplayName("余弦相似度：同向量=1，零向量=0，值域 [-1,1]")
    void cosineSimilarityBounds() {
        float[] v = VectorCalculator.vectorize("相似度验证");
        assertEquals(1.0f, VectorCalculator.cosineSimilarity(v, v), 1e-5);

        float[] zero = VectorCalculator.vectorize("");
        assertEquals(0.0f, VectorCalculator.cosineSimilarity(zero, v));

        float[] w = VectorCalculator.vectorize("另一段完全不同的文本");
        float similarity = VectorCalculator.cosineSimilarity(v, w);
        assertTrue(similarity >= -1.0f && similarity <= 1.0f, "相似度应落在 [-1,1]，实际 " + similarity);
    }

    @Test
    @DisplayName("相似度校验维度不一致与 null 入参")
    void cosineSimilarityRejectsInvalidInput() {
        float[] v256 = VectorCalculator.vectorize("维度校验");
        assertThrows(IllegalArgumentException.class,
                () -> VectorCalculator.cosineSimilarity(v256, new float[128]));
        assertThrows(IllegalArgumentException.class,
                () -> VectorCalculator.cosineSimilarity(null, v256));
    }

    @Test
    @DisplayName("向量编解码往返一致，长度 1024 字节")
    void codecRoundTrip() {
        float[] vector = VectorCalculator.vectorize("编解码往返验证");
        byte[] bytes = VectorCodec.encode(vector);
        assertEquals(VectorCalculator.DIMENSIONS * Float.BYTES, bytes.length);
        assertArrayEquals(vector, VectorCodec.decode(bytes));

        assertNotNull(VectorCodec.encode(VectorCalculator.vectorize("")));
        assertEquals(null, VectorCodec.encode(null));
        assertEquals(null, VectorCodec.decode(null));
    }
}
