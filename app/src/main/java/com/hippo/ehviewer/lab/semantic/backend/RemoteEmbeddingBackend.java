package com.hippo.ehviewer.lab.semantic.backend;

import android.util.Log;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.lab.semantic.SemanticConfig;
import com.hippo.ehviewer.lab.semantic.index.VectorMath;
import com.hippo.ehviewer.lab.semantic.model.EmbeddingPrompts;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 远程语义向量后端。
 *
 * <p>相比已有的 {@code OpenAiImageAnalyzer}，这里针对「嵌入」场景做了强化：
 * <ul>
 *   <li>支持三种协议风格：OpenAI {@code /v1/embeddings}、Ollama {@code /api/embed}、TEI {@code /embed}，
 *       因此局域网里跑的 llama.cpp server / vLLM / Ollama / TGI 都能直接接入</li>
 *   <li>批量提交（一次请求多条），并按 {@link SemanticConfig#BATCH_SIZE} 自动切块</li>
 *   <li>429 / 5xx 指数退避重试，带随机抖动，避免批量索引把服务打爆</li>
 *   <li>统一做 Matrioshka 截断 + L2 归一化 + 全有限校验，返回值与端侧后端完全一致</li>
 *   <li>{@link #probe()} 用一次真实请求验证连通性与模型可用性，用于设置页自检</li>
 * </ul>
 */
public class RemoteEmbeddingBackend implements EmbeddingBackend {

    private static final String TAG = "RemoteEmbeddingBackend";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static final int MAX_RETRY = 3;
    private static final long RETRY_BASE_DELAY_MS = 800L;

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final String protocol;
    private final int dim;

    private final OkHttpClient client;

    public RemoteEmbeddingBackend(String baseUrl, String apiKey, String model, String protocol, int dim) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.model = model;
        this.protocol = protocol != null ? protocol : SemanticConfig.PROTOCOL_OPENAI;
        this.dim = dim > 0 ? dim : SemanticConfig.DIM_256;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                // 远程 GPU 首次预热可能较慢，读超时给足
                .readTimeout(120, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    @Override
    public String id() {
        return SemanticConfig.BACKEND_REMOTE;
    }

    @Override
    public boolean isReady() {
        return baseUrl != null && !baseUrl.isEmpty();
    }

    @Override
    public int dimension() {
        return dim;
    }

    @Override
    public String modelId() {
        return model;
    }

    @Override
    public boolean supportsImage() {
        // 远程服务是否支持图像输入取决于具体引擎（Ollama/vLLM 视觉模型可以），
        // 这里保守返回 false，避免向不支持的服务发送图像请求。
        return false;
    }

    @Override
    public float[] embedQuery(String query) throws EmbeddingException {
        return embedOne(EmbeddingPrompts.query(query));
    }

    @Override
    public List<float[]> embedDocuments(List<String> documents) throws EmbeddingException {
        if (documents == null || documents.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> payloads = new ArrayList<>(documents.size());
        for (String doc : documents) {
            payloads.add(EmbeddingPrompts.document(doc));
        }
        List<float[]> result = new ArrayList<>(documents.size());
        for (int start = 0; start < payloads.size(); start += SemanticConfig.BATCH_SIZE) {
            int end = Math.min(payloads.size(), start + SemanticConfig.BATCH_SIZE);
            List<String> chunk = payloads.subList(start, end);
            List<float[]> vectors = post(chunk);
            if (vectors.size() != chunk.size()) {
                throw new EmbeddingException(EmbeddingException.BAD_RESPONSE,
                        "返回向量数量(" + vectors.size() + ")与请求数量(" + chunk.size() + ")不一致");
            }
            result.addAll(vectors);
        }
        return result;
    }

    @Override
    public float[] embedImage(android.graphics.Bitmap bitmap) throws EmbeddingException {
        throw new EmbeddingException(EmbeddingException.FATAL, "远程后端未启用图像输入");
    }

    @Override
    public void close() {
        try {
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /**
     * 连通性自检：发一次最短请求并校验返回值。
     *
     * @return 成功时返回实际向量维度，失败抛异常
     */
    public int probe() throws EmbeddingException {
        float[] vector = embedQuery("ping");
        return vector.length;
    }

    private float[] embedOne(String payload) throws EmbeddingException {
        List<float[]> vectors = post(java.util.Collections.singletonList(payload));
        if (vectors.isEmpty()) {
            throw new EmbeddingException(EmbeddingException.BAD_RESPONSE, "远程服务返回空向量");
        }
        return vectors.get(0);
    }

    private List<float[]> post(List<String> inputs) throws EmbeddingException {
        String endpoint = endpoint();
        String body = buildBody(inputs);

        IOException lastError = null;
        for (int attempt = 0; attempt <= MAX_RETRY; attempt++) {
            if (attempt > 0) {
                sleep(RETRY_BASE_DELAY_MS * (1L << (attempt - 1)) + randomJitter());
            }
            Request.Builder builder = new Request.Builder()
                    .url(endpoint)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept-Encoding", "gzip")
                    .post(RequestBody.create(JSON, body));
            if (!apiKey.isEmpty()) {
                builder.addHeader("Authorization", "Bearer " + apiKey);
            }
            try (Response response = client.newCall(builder.build()).execute()) {
                ResponseBody responseBody = response.body();
                String text = responseBody != null ? responseBody.string() : "";
                if (!response.isSuccessful()) {
                    if (isRetryable(response.code())) {
                        Log.w(TAG, "retryable http " + response.code() + ": " + trim(text));
                        lastError = new IOException("HTTP " + response.code() + ": " + trim(text));
                        continue;
                    }
                    throw new EmbeddingException(EmbeddingException.FATAL,
                            "HTTP " + response.code() + ": " + trim(text));
                }
                return parse(text);
            } catch (IOException e) {
                lastError = e;
                Log.w(TAG, "io error on attempt " + attempt + ": " + e);
            } catch (EmbeddingException e) {
                throw e;
            } catch (Throwable t) {
                throw new EmbeddingException(EmbeddingException.BAD_RESPONSE, "解析响应失败: " + t, t);
            }
        }
        throw new EmbeddingException(EmbeddingException.NETWORK_ERROR,
                "远程嵌入服务不可达: " + (lastError != null ? lastError.getMessage() : "unknown"), lastError);
    }

    private String endpoint() {
        switch (protocol) {
            case SemanticConfig.PROTOCOL_OLLAMA:
                return baseUrl.endsWith("/api/embed") ? baseUrl : baseUrl + "/api/embed";
            case SemanticConfig.PROTOCOL_TEI:
                return baseUrl + "/embed";
            case SemanticConfig.PROTOCOL_OPENAI:
            default:
                return baseUrl.endsWith("/embeddings") ? baseUrl : baseUrl + "/embeddings";
        }
    }

    private String buildBody(List<String> inputs) {
        JSONObject root = new JSONObject();
        switch (protocol) {
            case SemanticConfig.PROTOCOL_OLLAMA:
                root.put("model", model);
                root.put("input", new JSONArray(new ArrayList<>(inputs)));
                if (dim > 0 && dim < SemanticConfig.DIM_768) {
                    // Ollama 支持 dimensions 参数做 MRL 截断
                    root.put("dimensions", dim);
                }
                break;
            case SemanticConfig.PROTOCOL_TEI:
                root.put("inputs", new JSONArray(new ArrayList<>(inputs)));
                break;
            case SemanticConfig.PROTOCOL_OPENAI:
            default:
                root.put("model", model);
                root.put("input", new JSONArray(new ArrayList<>(inputs)));
                if (dim > 0 && dim < SemanticConfig.DIM_768) {
                    root.put("dimensions", dim);
                }
                break;
        }
        return root.toJSONString();
    }

    private List<float[]> parse(String text) throws EmbeddingException {
        switch (protocol) {
            case SemanticConfig.PROTOCOL_OLLAMA: {
                // {"embeddings": [[...], [...]]}
                JSONObject root = JSONObject.parseObject(text);
                JSONArray embeddings = root == null ? null : root.getJSONArray("embeddings");
                if (embeddings == null) {
                    throw new EmbeddingException(EmbeddingException.BAD_RESPONSE, "响应缺少 embeddings 字段");
                }
                return validated(toFloatArrayList(embeddings));
            }
            case SemanticConfig.PROTOCOL_TEI:
                return validated(parseTei(text));
            case SemanticConfig.PROTOCOL_OPENAI:
            default: {
                // {"data":[{"index":0,"embedding":[...]}, ...]}
                JSONObject root = JSONObject.parseObject(text);
                JSONArray data = root == null ? null : root.getJSONArray("data");
                if (data == null) {
                    throw new EmbeddingException(EmbeddingException.BAD_RESPONSE, "响应缺少 data 字段");
                }
                List<float[]> vectors = new ArrayList<>(data.size());
                for (int i = 0; i < data.size(); i++) {
                    vectors.add(toFloatArray(data.getJSONObject(i).getJSONArray("embedding")));
                }
                return validated(vectors);
            }
        }
    }

    /**
     * 解析 TEI {@code /embed} 响应。
     *
     * <p>TEI 对单条输入返回 {@code [[...]]}，对多条输入返回 {@code [[[...]], [[...]]]}，
     * 这里同时兼容两种形态。
     */
    private static List<float[]> parseTei(String text) throws EmbeddingException {
        JSONArray root = com.alibaba.fastjson.JSON.parseArray(text);
        if (root == null || root.isEmpty()) {
            throw new EmbeddingException(EmbeddingException.BAD_RESPONSE, "TEI 响应为空");
        }
        Object first = root.get(0);
        if (!(first instanceof JSONArray)) {
            throw new EmbeddingException(EmbeddingException.BAD_RESPONSE, "TEI 响应结构无法识别");
        }
        JSONArray firstArr = (JSONArray) first;
        if (firstArr.isEmpty()) {
            throw new EmbeddingException(EmbeddingException.BAD_RESPONSE, "TEI 响应向量为空");
        }
        boolean nested = firstArr.get(0) instanceof JSONArray;
        List<float[]> vectors = new ArrayList<>(root.size());
        for (int i = 0; i < root.size(); i++) {
            JSONArray item = root.getJSONArray(i);
            if (nested) {
                // [[[...]], [[...]]] 每个元素还包一层
                JSONArray inner = item != null && !item.isEmpty() ? item.getJSONArray(0) : null;
                vectors.add(toFloatArray(inner));
            } else {
                vectors.add(toFloatArray(item));
            }
        }
        return vectors;
    }

    private static List<float[]> toFloatArrayList(JSONArray array) {
        List<float[]> vectors = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            vectors.add(toFloatArray(array.getJSONArray(i)));
        }
        return vectors;
    }

    private List<float[]> validated(List<float[]> raw) throws EmbeddingException {
        List<float[]> out = new ArrayList<>(raw.size());
        for (float[] vector : raw) {
            if (!VectorMath.isValid(vector)) {
                throw new EmbeddingException(EmbeddingException.BAD_RESPONSE,
                        "远程返回了非法向量（NaN / Inf / 全零）");
            }
            out.add(VectorMath.truncateAndNormalize(vector, dim));
        }
        return out;
    }

    private static float[] toFloatArray(JSONArray array) {
        if (array == null) {
            return null;
        }
        float[] out = new float[array.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = array.getFloatValue(i);
        }
        return out;
    }

    private static boolean isRetryable(int code) {
        return code == 408 || code == 429 || (code >= 500 && code < 600);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long randomJitter() {
        return (long) (Math.random() * 400d);
    }

    private static String trim(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}