package com.hippo.ehviewer.lab.semantic.backend;

import android.graphics.Bitmap;

import java.util.List;

/** 关闭态后端：所有调用直接返回空，保证上层无需判空即可安全降级。 */
public class DisabledBackend implements EmbeddingBackend {

    @Override
    public String id() {
        return com.hippo.ehviewer.lab.semantic.SemanticConfig.BACKEND_OFF;
    }

    @Override
    public boolean isReady() {
        return false;
    }

    @Override
    public int dimension() {
        return com.hippo.ehviewer.lab.semantic.SemanticConfig.DIM_256;
    }

    @Override
    public String modelId() {
        return "";
    }

    @Override
    public boolean supportsImage() {
        return false;
    }

    @Override
    public float[] embedQuery(String query) throws EmbeddingException {
        throw new EmbeddingException(EmbeddingException.FATAL, "语义搜索未启用");
    }

    @Override
    public List<float[]> embedDocuments(List<String> documents) throws EmbeddingException {
        throw new EmbeddingException(EmbeddingException.FATAL, "语义搜索未启用");
    }

    @Override
    public float[] embedImage(Bitmap bitmap) throws EmbeddingException {
        throw new EmbeddingException(EmbeddingException.FATAL, "语义搜索未启用");
    }

    @Override
    public void close() {
    }
}