package com.hippo.ehviewer.lab.semantic.backend;

import android.graphics.Bitmap;

import java.util.List;

/**
 * 语义向量后端抽象。
 *
 * <p>三种实现：
 * <ul>
 *   <li>{@link NativeLlamaBackend} —— 端侧 llama.cpp（CPU / Vulkan GPU）</li>
 *   <li>{@link RemoteEmbeddingBackend} —— 远程 OpenAI 兼容 / Ollama / TEI 接口（含局域网自建 GPU 服务）</li>
 *   <li>{@code DisabledBackend} —— 关闭态</li>
 * </ul>
 *
 * <p>约定：所有返回向量均为 L2 归一化后的 float[]，长度 = {@link #dimension()}。
 * 实现必须自行把 Matrioshka 截断（768/512/256/128）做对。
 */
public interface EmbeddingBackend {

    /** 后端标识：native_cpu / native_gpu / remote / disabled。 */
    String id();

    /** 是否已就绪（模型已加载或远程配置完整）。 */
    boolean isReady();

    /** 向量维度。 */
    int dimension();

    /** 模型标识（GGUF 文件名或远程模型名）。 */
    String modelId();

    /** 是否支持图像输入。 */
    boolean supportsImage();

    /**
     * 编码查询串（自动套用 search result 任务前缀）。
     *
     * @return L2 归一化向量，长度 {@link #dimension()}
     */
    float[] embedQuery(String query) throws EmbeddingException;

    /**
     * 批量编码文档（自动套用 document 任务前缀）。
     * 实现应尽量在一次前向里处理多条以提升吞吐。
     */
    List<float[]> embedDocuments(List<String> documents) throws EmbeddingException;

    /**
     * 编码图像（仅当 {@link #supportsImage()} 为 true）。
     */
    float[] embedImage(Bitmap bitmap) throws EmbeddingException;

    /** 主动释放模型 / 连接等资源。 */
    void close();
}