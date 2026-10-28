package com.hippo.ehviewer.lab.semantic;

/**
 * 语义搜索（实验室功能）的全局常量与取值定义。
 *
 * <p>后端选择遵循「自动降级链」：native_gpu → native_cpu → remote → disabled。
 * 自动档位由 {@link com.hippo.ehviewer.lab.semantic.capability.CapabilityDetector}
 * 在运行时探测平台能力得出，用户可在实验室设置中覆盖。
 */
public final class SemanticConfig {

    private SemanticConfig() {
    }

    /** 模型与索引文件的存放目录名（位于 filesDir 下）。 */
    public static final String MODEL_DIR_NAME = "semantic-models";

    /** 独立的向量索引数据库文件名，避免污染主 GreenDAO 库。 */
    public static final String VECTOR_DB_NAME = "semantic_index.db";

    /** 后端模式：自动（按平台能力选择）。 */
    public static final String BACKEND_AUTO = "auto";
    /** 后端模式：端侧 llama.cpp CPU。 */
    public static final String BACKEND_NATIVE_CPU = "native_cpu";
    /** 后端模式：端侧 llama.cpp Vulkan GPU（仅增强档）。 */
    public static final String BACKEND_NATIVE_GPU = "native_gpu";
    /** 后端模式：远程 OpenAI 兼容嵌入接口（含局域网自建 GPU 服务）。 */
    public static final String BACKEND_REMOTE = "remote";
    /** 后端模式：关闭。 */
    public static final String BACKEND_OFF = "off";

    /** 能力档位：增强（>=10GB 内存，可选多模态与 GPU 批量索引）。 */
    public static final String TIER_ENHANCED = "enhanced";
    /** 能力档位：标准（arm64 + 4~8GB，纯文本模型）。 */
    public static final String TIER_STANDARD = "standard";
    /** 能力档位：远程（无 arm64 native 或内存过小）。 */
    public static final String TIER_REMOTE = "remote";
    /** 能力档位：不支持。 */
    public static final String TIER_UNSUPPORTED = "unsupported";

    /** 远程协议的请求/响应风格。 */
    public static final String PROTOCOL_OPENAI = "openai";
    public static final String PROTOCOL_OLLAMA = "ollama";
    public static final String PROTOCOL_TEI = "tei";

    /** 后端健康状态。 */
    public static final String STATE_UNKNOWN = "unknown";
    public static final String STATE_READY = "ready";
    public static final String STATE_DEGRADED = "degraded";
    public static final String STATE_ERROR = "error";

    /** 索引条目类别。 */
    public static final int KIND_GALLERY = 0;
    public static final int KIND_TAG = 1;

    /** 单条文档编码前保留的最大字符数，超出截断（防止超长标题/简介拖垮索引）。 */
    public static final int MAX_DOC_CHARS = 1200;

    /** 批量编码时单次提交给后端的最大条目数。 */
    public static final int BATCH_SIZE = 16;

    /** 默认向量维度（MRL 截断：768 / 512 / 256 / 128）。 */
    public static final int DIM_768 = 768;
    public static final int DIM_512 = 512;
    public static final int DIM_256 = 256;
    public static final int DIM_128 = 128;

    /** 内存档位阈值（字节）。 */
    public static final long RAM_10GB = 10L * 1024 * 1024 * 1024;
    public static final long RAM_4GB = 4L * 1024 * 1024 * 1024;

    /** 下载模型所需的最小可用存储（字节）。 */
    public static final long MIN_FREE_STORAGE = 700L * 1024 * 1024;
}