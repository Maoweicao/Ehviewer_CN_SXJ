package com.hippo.ehviewer.lab.semantic.model;

import android.content.Context;
import android.util.Log;

import com.hippo.ehviewer.AppConfig;
import com.hippo.ehviewer.lab.semantic.SemanticConfig;
import com.hippo.ehviewer.lab.semantic.capability.CapabilityProfile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 端侧模型文件的下载与本地管理。
 *
 * <p>参照 {@code EhTagDatabase} 的下载范式：临时文件 + 原子替换 + 断点续传；
 * 主源（HuggingFace）失败自动切换到 hf-mirror 镜像，避免国内网络下无法下载。
 */
public class ModelManager {

    private static final String TAG = "SemanticModelManager";

    private final Context appContext;
    private final File modelDir;
    private final OkHttpClient client;
    private final ExecutorService executor;
    private final AtomicBoolean downloading = new AtomicBoolean(false);

    public ModelManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.modelDir = AppConfig.getFilesDir(SemanticConfig.MODEL_DIR_NAME);
        this.client = new OkHttpClient.Builder()
                .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "SemanticModelDownload");
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }

    public File getModelDir() {
        return modelDir;
    }

    public boolean isDownloading() {
        return downloading.get();
    }

    /**
     * 根据能力档案挑选可用模型：优先用户指定的，其次当前档位的默认模型。
     *
     * @return 已安装模型的本地路径；未安装返回 null
     */
    public String resolveModelPath(CapabilityProfile profile) {
        List<ModelSpec> candidates = new ArrayList<>();
        String preferredId = com.hippo.ehviewer.Settings.getSemanticModelId();
        if (preferredId != null && !preferredId.isEmpty()) {
            ModelSpec preferred = ModelCatalog.get(preferredId);
            if (isInstalled(preferred)) {
                return localFile(preferred).getAbsolutePath();
            }
        }
        candidates.addAll(ModelCatalog.listForTier(profile.tier()));

        for (ModelSpec spec : candidates) {
            if (isInstalled(spec)) {
                return localFile(spec).getAbsolutePath();
            }
        }
        // 降级：任何已安装的模型都可用，维度截断会自动生效
        for (ModelSpec spec : ModelCatalog.all()) {
            if (isInstalled(spec)) {
                return localFile(spec).getAbsolutePath();
            }
        }
        return null;
    }

    public File localFile(ModelSpec spec) {
        return new File(modelDir, spec.localName());
    }

    public boolean isInstalled(ModelSpec spec) {
        File file = localFile(spec);
        return file.exists() && file.length() > 0;
    }

    /** 已安装模型占用的总字节数。 */
    public long installedSize() {
        long total = 0L;
        for (ModelSpec spec : ModelCatalog.all()) {
            File file = localFile(spec);
            if (file.exists()) {
                total += file.length();
            }
        }
        return total;
    }

    /** 列出已安装的模型。 */
    public List<ModelSpec> installed() {
        List<ModelSpec> result = new ArrayList<>();
        for (ModelSpec spec : ModelCatalog.all()) {
            if (isInstalled(spec)) {
                result.add(spec);
            }
        }
        return result;
    }

    public boolean delete(ModelSpec spec) {
        File file = localFile(spec);
        return file.exists() && file.delete();
    }

    /** 下载进度回调。 */
    public interface DownloadCallback {
        void onProgress(long downloaded, long total);

        void onSuccess(File file);

        void onError(String error);
    }

    /**
     * 异步下载模型。主源与镜像源依次尝试，写入 .part 临时文件后原子重命名。
     */
    public void download(final ModelSpec spec, final DownloadCallback callback) {
        if (!downloading.compareAndSet(false, true)) {
            callback.onError("已有模型正在下载");
            return;
        }
        executor.execute(() -> {
            try {
                File target = localFile(spec);
                if (target.exists() && target.length() > 0) {
                    callback.onSuccess(target);
                    return;
                }
                ensureDir();

                String[] urls = {spec.officialUrl(), spec.mirrorUrl()};
                String lastError = null;
                for (String url : urls) {
                    File part = new File(modelDir, spec.localName() + ".part");
                    try {
                        downloadTo(url, part, callback);
                        // 校验：明显短于预期说明下载不完整
                        if (part.length() < spec.approxSizeBytes * 0.6) {
                            throw new IOException("文件不完整: " + part.length() + " bytes");
                        }
                        replaceAtomically(part, target);
                        callback.onSuccess(target);
                        return;
                    } catch (IOException e) {
                        lastError = e.getMessage();
                        Log.w(TAG, "source failed " + url + ": " + lastError);
                        //noinspection ResultOfMethodCallIgnored
                        part.delete();
                    }
                }
                callback.onError("下载失败: " + (lastError != null ? lastError : "未知错误"));
            } catch (Throwable t) {
                Log.e(TAG, "download crash", t);
                callback.onError(String.valueOf(t.getMessage()));
            } finally {
                downloading.set(false);
            }
        });
    }

    private void ensureDir() throws IOException {
        if (!modelDir.exists() && !modelDir.mkdirs()) {
            throw new IOException("无法创建模型目录: " + modelDir);
        }
    }

    /** 支持 Range 断点续传。 */
    private void downloadTo(String url, File part, DownloadCallback callback) throws IOException {
        long existing = part.exists() ? part.length() : 0L;
        Request.Builder builder = new Request.Builder().url(url).get();
        if (existing > 0) {
            builder.addHeader("Range", "bytes=" + existing + "-");
            Log.i(TAG, "resume from " + existing);
        }

        try (Response response = client.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("空响应体");
            }
            long total = existing + body.contentLength();
            boolean append = existing > 0 && response.code() == 206;
            try (InputStream in = body.byteStream();
                 OutputStream out = new FileOutputStream(part, append)) {
                byte[] buffer = new byte[64 * 1024];
                long downloaded = existing;
                int read;
                long lastNotify = 0L;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    downloaded += read;
                    if (downloaded - lastNotify > 512 * 1024) {
                        callback.onProgress(downloaded, total);
                        lastNotify = downloaded;
                    }
                }
                out.flush();
            }
            callback.onProgress(total > 0 ? total : part.length(), total);
        }
    }

    private void replaceAtomically(File part, File target) throws IOException {
        if (target.exists() && !target.delete()) {
            throw new IOException("无法覆盖旧文件");
        }
        if (!part.renameTo(target)) {
            // 某些文件系统上 rename 到已存在文件会失败，退化为复制
            if (!copy(part, target)) {
                throw new IOException("无法重命名临时文件");
            }
            //noinspection ResultOfMethodCallIgnored
            part.delete();
        }
    }

    private boolean copy(File from, File to) throws IOException {
        try (InputStream in = new java.io.FileInputStream(from);
             OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }
        return true;
    }

    public static String formatSize(long bytes) {
        if (bytes >= 1024 * 1024 * 1024L) {
            return String.format(Locale.US, "%.2f GB", bytes / (1024f * 1024f * 1024f));
        }
        if (bytes >= 1024 * 1024L) {
            return String.format(Locale.US, "%.0f MB", bytes / (1024f * 1024f));
        }
        return bytes + " B";
    }
}