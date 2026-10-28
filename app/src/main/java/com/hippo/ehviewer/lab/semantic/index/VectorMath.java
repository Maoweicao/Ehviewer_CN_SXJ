package com.hippo.ehviewer.lab.semantic.index;

import java.util.Arrays;

/**
 * 向量数学工具：Matrioshka 截断、L2 归一化、int8 量化、余弦相似度。
 *
 * <p>存储侧统一使用 int8 量化（每条向量一个 float scale），
 * 万级条目下暴力扫描耗时可忽略，无需引入向量数据库。
 */
public final class VectorMath {

    /** int8 量化时使用的对称范围。 */
    private static final int INT8_MAX = 127;

    private VectorMath() {
    }

    /** 截断到指定维度并重新归一化。维度不合法时原样返回。 */
    public static float[] truncateAndNormalize(float[] vector, int dim) {
        if (vector == null) {
            return null;
        }
        float[] out = vector;
        if (dim > 0 && vector.length != dim) {
            if (vector.length < dim) {
                // 维度不足时补零，避免后续比较出现长度错配
                out = Arrays.copyOf(vector, dim);
            } else {
                out = Arrays.copyOf(vector, dim);
            }
        }
        return l2Normalize(out);
    }

    /** L2 归一化，就地返回新数组；输入含 NaN/Inf 时返回 null。 */
    public static float[] l2Normalize(float[] vector) {
        if (vector == null) {
            return null;
        }
        double sum = 0d;
        for (float v : vector) {
            if (!Float.isFinite(v)) {
                return null;
            }
            sum += (double) v * v;
        }
        if (sum <= 1e-12d) {
            return null;
        }
        float inv = (float) (1d / Math.sqrt(sum));
        for (int i = 0; i < vector.length; i++) {
            vector[i] *= inv;
        }
        return vector;
    }

    /** 校验远程/端侧返回的向量是否可用（全有限、非零）。 */
    public static boolean isValid(float[] vector) {
        if (vector == null || vector.length == 0) {
            return false;
        }
        double sum = 0d;
        for (float v : vector) {
            if (!Float.isFinite(v)) {
                return false;
            }
            sum += (double) v * v;
        }
        return sum > 1e-12d;
    }

    /**
     * int8 对称量化。
     *
     * @return 长度 4+dim 的字节数组：前 4 字节为 scale（float），其后为 int8 分量
     */
    public static byte[] quantizeInt8(float[] vector) {
        int dim = vector.length;
        byte[] out = new byte[4 + dim];
        float max = 0f;
        for (float v : vector) {
            max = Math.max(max, Math.abs(v));
        }
        float scale = max > 0f ? max / INT8_MAX : 1f;
        byte[] scaleBytes = new byte[4];
        int bits = Float.floatToRawIntBits(scale);
        scaleBytes[0] = (byte) (bits & 0xFF);
        scaleBytes[1] = (byte) ((bits >> 8) & 0xFF);
        scaleBytes[2] = (byte) ((bits >> 16) & 0xFF);
        scaleBytes[3] = (byte) ((bits >> 24) & 0xFF);
        System.arraycopy(scaleBytes, 0, out, 0, 4);
        float inv = 1f / scale;
        for (int i = 0; i < dim; i++) {
            int q = Math.round(vector[i] * inv);
            q = Math.max(-127, Math.min(127, q));
            out[4 + i] = (byte) q;
        }
        return out;
    }

    /** 反量化 int8 blob 为 float 向量。 */
    public static float[] dequantizeInt8(byte[] blob) {
        if (blob == null || blob.length <= 4) {
            return null;
        }
        int bits = (blob[0] & 0xFF)
                | ((blob[1] & 0xFF) << 8)
                | ((blob[2] & 0xFF) << 16)
                | ((blob[3] & 0xFF) << 24);
        float scale = Float.intBitsToFloat(bits);
        int dim = blob.length - 4;
        float[] out = new float[dim];
        for (int i = 0; i < dim; i++) {
            out[i] = blob[4 + i] * scale;
        }
        return out;
    }

    /**
     * int8 blob 与查询向量的余弦相似度。
     *
     * <p>查询向量是归一化 float[]，索引向量是 int8 量化向量，
     * 直接在量化域做点积再乘 scale 即可，无需完整反量化。
     */
    public static float cosineInt8(byte[] blob, float[] query) {
        if (blob == null || query == null) {
            return 0f;
        }
        int dim = blob.length - 4;
        if (dim != query.length) {
            return 0f;
        }
        int bits = (blob[0] & 0xFF)
                | ((blob[1] & 0xFF) << 8)
                | ((blob[2] & 0xFF) << 16)
                | ((blob[3] & 0xFF) << 24);
        float scale = Float.intBitsToFloat(bits);
        double dot = 0d;
        for (int i = 0; i < dim; i++) {
            dot += (double) (blob[4 + i] & 0xFF) * query[i];
        }
        return (float) (dot * scale);
    }

    /** 两个归一化向量的余弦相似度。 */
    public static float cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0f;
        }
        double dot = 0d;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
        }
        return (float) dot;
    }
}