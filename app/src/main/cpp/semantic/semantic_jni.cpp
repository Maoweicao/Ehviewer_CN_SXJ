// Embedding backend JNI glue for the lab "semantic search" feature.
//
// Two build modes, selected by CMake:
//   1) EHVIEWER_LLAMA_AVAILABLE defined  -> real llama.cpp inference
//   2) otherwise                          -> stub that reports "unavailable"
//
// The stub mode is intentional: it keeps `libsemantic.so` always loadable so
// NativeLlamaBackend can call nativeProbe() and cleanly fall back to the remote
// backend, instead of throwing UnsatisfiedLinkError at an arbitrary call site.
//
// Expected llama.cpp API: b5xxx+ (llama_model_load_from_file / llama_init_from_model /
// llama_encode + llama_get_embeddings_seq). If the vendored version drifts, the only
// thing that needs adjusting is the block below marked "llama.cpp API".

#include <jni.h>
#include <android/log.h>

#include <cmath>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#define LOG_TAG "SemanticJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

#ifdef EHVIEWER_LLAMA_AVAILABLE

#include "ggml-backend.h"
#include "llama.h"

namespace {

struct SemanticContext {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    int dim = 256;
    bool gpu = false;
    std::mutex mutex;
};

// Matrioshka truncation + L2 normalize, matching the Java-side VectorMath.
std::vector<float> truncate_and_normalize(const float *src, int src_len, int dim) {
    int out_dim = (dim > 0 && dim < src_len) ? dim : src_len;
    std::vector<float> out(out_dim);
    double sum = 0.0;
    for (int i = 0; i < out_dim; i++) {
        if (!std::isfinite(src[i])) {
            LOGW("non-finite value at %d -> return empty", i);
            return {};
        }
        out[i] = src[i];
        sum += static_cast<double>(out[i]) * out[i];
    }
    if (sum <= 1e-12) {
        return {};
    }
    float inv = static_cast<float>(1.0 / std::sqrt(sum));
    for (int i = 0; i < out_dim; i++) {
        out[i] *= inv;
    }
    return out;
}

float *embed_payload(SemanticContext *sc, const std::string &payload) {
    if (sc == nullptr || sc->model == nullptr || sc->ctx == nullptr) {
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(sc->mutex);

    // --- llama.cpp API: tokenize ---
    int n_tokens_max = static_cast<int>(payload.size()) + 16;
    std::vector<llama_token> tokens(n_tokens_max);
    int n_tokens = llama_tokenize(sc->model,
                                  payload.data(),
                                  static_cast<int32_t>(payload.size()),
                                  tokens.data(),
                                  n_tokens_max,
                                  /*add_special=*/true,
                                  /*parse_special=*/false);
    if (n_tokens <= 0) {
        n_tokens = std::min(n_tokens, 0);
        LOGW("tokenize failed: %d", n_tokens);
        return nullptr;
    }
    tokens.resize(n_tokens);

    llama_memory_clear(llama_get_memory(sc->ctx), true);

    llama_batch batch = llama_batch_init(static_cast<uint32_t>(n_tokens), 0, 1);
    for (int i = 0; i < n_tokens; i++) {
        common_batch_add(batch, tokens[i], i, {0}, true);
    }

    if (llama_encode(sc->ctx, batch) != 0) {
        LOGW("llama_encode failed");
        llama_batch_free(batch);
        return nullptr;
    }

    // Mean pooling over the sequence (matching llama-server --pooling mean).
    const float *embd = llama_get_embeddings_seq(sc->ctx, 0);
    if (embd == nullptr) {
        LOGW("no embeddings returned (is the model an embedding model?)");
        llama_batch_free(batch);
        return nullptr;
    }
    int embd_dim = llama_model_n_embd(sc->model);
    std::vector<float> pooled(embd_dim, 0.0f);
    for (int i = 0; i < n_tokens; i++) {
        for (int d = 0; d < embd_dim; d++) {
            pooled[d] += embd[i * embd_dim + d];
        }
    }
    for (int d = 0; d < embd_dim; d++) {
        pooled[d] /= static_cast<float>(n_tokens);
    }
    llama_batch_free(batch);

    std::vector<float> result = truncate_and_normalize(pooled.data(), embd_dim, sc->dim);
    if (result.empty()) {
        return nullptr;
    }
    return new float[result.size()]{result.begin(), result.end()};
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_hippo_ehviewer_lab_semantic_backend_NativeLlamaBackend_nativeProbe(JNIEnv *, jobject) {
    LOGI("native probe: llama.cpp build detected");
    return JNI_TRUE;
}

JNIEXPORT jlong JNICALL
Java_com_hippo_ehviewer_lab_semantic_backend_NativeLlamaBackend_nativeLoad(
        JNIEnv *env, jobject, jstring jPath, jint jThreads, jboolean jUseGpu, jint jDim) {
    const char *path = env->GetStringUTFChars(jPath, nullptr);
    if (path == nullptr) {
        return 0;
    }

    // Register backends once; harmless if called repeatedly.
    static std::once_flag backend_once;
    std::call_once(backend_once, []() {
        ggml_backend_load_all();
        LOGI("ggml backends registered: %zu", ggml_backend_reg_count());
    });

    llama_backend_init();

    auto *sc = new SemanticContext();
    sc->dim = jDim > 0 ? jDim : 256;
    sc->gpu = jUseGpu == JNI_TRUE;

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = sc->gpu ? 99 : 0;
    mparams.use_mmap = true;

    sc->model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jPath, path);
    if (sc->model == nullptr) {
        LOGW("failed to load model (bad GGUF or unsupported quant?)");
        delete sc;
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    // Embedding models only need a modest context; queries are short.
    cparams.n_ctx = 2048;
    cparams.n_batch = 512;
    cparams.n_ubatch = 512;
    cparams.n_threads = jThreads > 0 ? jThreads : 4;
    cparams.n_threads_batch = cparams.n_threads;
    cparams.embeddings = true;
    cparams.pooling_type = LLAMA_POOLING_TYPE_MEAN;

    sc->ctx = llama_init_from_model(sc->model, cparams);
    if (sc->ctx == nullptr) {
        LOGW("failed to create llama context");
        llama_model_free(sc->model);
        delete sc;
        return 0;
    }

    LOGI("model loaded: n_embd=%d threads=%d gpu=%d", llama_model_n_embd(sc->model),
         cparams.n_threads, sc->gpu ? 1 : 0);
    return reinterpret_cast<jlong>(sc);
}

JNIEXPORT jfloatArray JNICALL
Java_com_hippo_ehviewer_lab_semantic_backend_NativeLlamaBackend_nativeEmbed(
        JNIEnv *env, jobject, jlong handle, jstring jPayload, jint jDim) {
    auto *sc = reinterpret_cast<SemanticContext *>(handle);
    if (sc == nullptr) {
        return nullptr;
    }
    const char *payload = env->GetStringUTFChars(jPayload, nullptr);
    if (payload == nullptr) {
        return nullptr;
    }
    std::string text(payload);
    env->ReleaseStringUTFChars(jPayload, payload);

    float *vec = embed_payload(sc, text);
    if (vec == nullptr) {
        return nullptr;
    }
    int dim = (jDim > 0) ? jDim : sc->dim;
    jfloatArray out = env->NewFloatArray(dim);
    if (out != nullptr) {
        env->SetFloatArrayRegion(out, 0, dim, vec);
    }
    delete[] vec;
    return out;
}

JNIEXPORT void JNICALL
Java_com_hippo_ehviewer_lab_semantic_backend_NativeLlamaBackend_nativeFree(
        JNIEnv *, jobject, jlong handle) {
    auto *sc = reinterpret_cast<SemanticContext *>(handle);
    if (sc == nullptr) {
        return;
    }
    if (sc->ctx != nullptr) {
        llama_free(sc->ctx);
    }
    if (sc->model != nullptr) {
        llama_model_free(sc->model);
    }
    delete sc;
    llama_backend_free();
}

}  // extern "C"

#else  // !EHVIEWER_LLAMA_AVAILABLE — stub build

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_hippo_ehviewer_lab_semantic_backend_NativeLlamaBackend_nativeProbe(JNIEnv *, jobject) {
    LOGI("stub build: llama.cpp not vendored, native backend unavailable");
    return JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_hippo_ehviewer_lab_semantic_backend_NativeLlamaBackend_nativeLoad(
        JNIEnv *, jobject, jstring, jint, jboolean, jint) {
    return 0;
}

JNIEXPORT jfloatArray JNICALL
Java_com_hippo_ehviewer_lab_semantic_backend_NativeLlamaBackend_nativeEmbed(
        JNIEnv *, jobject, jlong, jstring, jint) {
    return nullptr;
}

JNIEXPORT void JNICALL
Java_com_hippo_ehviewer_lab_semantic_backend_NativeLlamaBackend_nativeFree(
        JNIEnv *, jobject, jlong) {
}

}  // extern "C"

#endif  // EHVIEWER_LLAMA_AVAILABLE