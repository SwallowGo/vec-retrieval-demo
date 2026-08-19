package com.twelvetimers.vector.service.embedding;

import java.nio.ByteBuffer;

/**
 * 向量与字节数组互转，用于持久化到 H2 的 VARBINARY 列。
 *
 * <p>float 按大端序逐位编码，长度 = 维度 × 4 字节（256 维即 1024 字节）。
 */
public final class VectorCodec {

    private VectorCodec() {
    }

    public static byte[] encode(float[] vector) {
        if (vector == null) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    public static float[] decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        if (bytes.length % Float.BYTES != 0) {
            throw new IllegalArgumentException("非法向量字节长度: " + bytes.length);
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        float[] vector = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }
}
