package com.hippo.ehviewer.lab.semantic.backend;

/**
 * 语义向量后端异常。{@link #code} 用于判定失败是否可降级（见 {@link EmbeddingException#FATAL}）。
 */
public class EmbeddingException extends Exception {

    private static final long serialVersionUID = 1L;

    /** 端侧 native 不可用（so 缺失 / ABI 不匹配 / 加载失败），可降级远程。 */
    public static final String NATIVE_UNAVAILABLE = "native_unavailable";
    /** 模型文件缺失或损坏，需要重新下载。 */
    public static final String MODEL_MISSING = "model_missing";
    /** 推理失败（可重试）。 */
    public static final String INFERENCE_FAILED = "inference_failed";
    /** 远程网络错误。 */
    public static final String NETWORK_ERROR = "network_error";
    /** 远程返回了非法向量（非有限值 / 维度不符）。 */
    public static final String BAD_RESPONSE = "bad_response";
    /** 用户主动中断。 */
    public static final String CANCELLED = "cancelled";
    /** 不可恢复错误。 */
    public static final String FATAL = "fatal";

    private final String code;

    public EmbeddingException(String code, String message) {
        super(message);
        this.code = code;
    }

    public EmbeddingException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /** 是否可以降级到其它后端继续工作。 */
    public boolean isDegradable() {
        return NATIVE_UNAVAILABLE.equals(code) || MODEL_MISSING.equals(code)
                || NETWORK_ERROR.equals(code) || INFERENCE_FAILED.equals(code);
    }
}