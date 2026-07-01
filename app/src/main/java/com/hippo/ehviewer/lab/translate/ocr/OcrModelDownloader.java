package com.hippo.ehviewer.lab.translate.ocr;

import android.app.ProgressDialog;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Environment;
import android.util.Log;

import com.hippo.ehviewer.Settings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

public class OcrModelDownloader {
    private static final String TAG = "OcrModelDownloader";

    // ModelScope模型链接
    private static final String PP_OCRV6_MEDIUM_REC_BASE = "https://modelscope.cn/models/PaddlePaddle/PP-OCRv6_medium_rec_onnx/resolve/master/";
    
    // HuggingFace模型链接
    private static final String PP_OCRV5_BASE = "https://huggingface.co/PaddlePaddle/PP-OCRv5/resolve/main/";
    private static final String PADDLE_OCR_VL_BASE = "https://huggingface.co/PaddlePaddle/PaddleOCR-VL-1.6/resolve/main/";

    // PP-OCRv6_medium_rec_onnx 模型文件
    private static final String[] PP_OCRV6_MEDIUM_REC_FILES = {
            "inference.onnx",
            "inference.yml"
    };

    // PP-OCRv5模型文件
    private static final String[] PP_OCRV5_FILES = {
            "inference/det/inference.pdiparams",
            "inference/det/inference.yml",
            "inference/rec/inference.pdiparams",
            "inference/rec/inference.yml",
            "inference/cls/inference.pdiparams",
            "inference/cls/inference.yml"
    };

    // PaddleOCR-VL模型文件
    private static final String[] PADDLE_OCR_VL_FILES = {
            "model.safetensors",
            "config.json",
            "tokenizer.json"
    };

    public enum ModelType {
        PP_OCRV6_MEDIUM_REC,
        PP_OCRV5,
        PADDLE_OCR_VL
    }

    public interface DownloadCallback {
        void onProgress(int progress, String currentFile);
        void onSuccess(File modelDir);
        void onError(String error);
    }

    public static String getBaseModelDir(Context context) {
        // 优先使用外部存储的 ehviewer/model 目录
        File externalDir = context.getExternalFilesDir(null);
        if (externalDir != null) {
            return new File(externalDir.getParentFile().getParentFile(), "ehviewer/model").getAbsolutePath();
        }
        // 回退到内部存储
        return new File(context.getFilesDir(), "ehviewer/model").getAbsolutePath();
    }

    public static String getOcrModelDir(Context context) {
        String baseDir = Settings.getAiOcrModelDownloadPath();
        if (baseDir != null && !baseDir.isEmpty()) {
            return baseDir;
        }
        return getBaseModelDir(context) + "/ocr";
    }

    public static String getAiModelDir(Context context) {
        String baseDir = Settings.getAiModelDownloadPath();
        if (baseDir != null && !baseDir.isEmpty()) {
            return baseDir;
        }
        return getBaseModelDir(context) + "/ai";
    }

    public static void downloadModel(Context context, ModelType type, DownloadCallback callback) {
        downloadModel(context, type, null, callback);
    }

    public static void downloadModel(Context context, ModelType type, String customDownloadDir, DownloadCallback callback) {
        String[] files;
        String baseUrl;
        String modelDirName;
        String downloadDir;

        switch (type) {
            case PP_OCRV6_MEDIUM_REC:
                files = PP_OCRV6_MEDIUM_REC_FILES;
                baseUrl = PP_OCRV6_MEDIUM_REC_BASE;
                modelDirName = "pp_ocrv6_medium_rec";
                break;
            case PP_OCRV5:
                files = PP_OCRV5_FILES;
                baseUrl = PP_OCRV5_BASE;
                modelDirName = "pp_ocrv5";
                break;
            case PADDLE_OCR_VL:
                files = PADDLE_OCR_VL_FILES;
                baseUrl = PADDLE_OCR_VL_BASE;
                modelDirName = "paddle_ocr_vl";
                break;
            default:
                callback.onError("Unknown model type");
                return;
        }

        // 确定下载目录
        if (customDownloadDir != null && !customDownloadDir.isEmpty()) {
            downloadDir = customDownloadDir;
        } else {
            downloadDir = getOcrModelDir(context);
        }

        new DownloadTask(context, baseUrl, files, downloadDir, modelDirName, callback).execute();
    }

    public static boolean isModelDownloaded(Context context, ModelType type) {
        File modelDir = getModelDir(context, type);
        if (modelDir == null || !modelDir.exists()) return false;

        File[] files = modelDir.listFiles();
        return files != null && files.length > 0;
    }

    public static File getModelDir(Context context, ModelType type) {
        String modelDirName;
        switch (type) {
            case PP_OCRV6_MEDIUM_REC:
                modelDirName = "pp_ocrv6_medium_rec";
                break;
            case PP_OCRV5:
                modelDirName = "pp_ocrv5";
                break;
            case PADDLE_OCR_VL:
                modelDirName = "paddle_ocr_vl";
                break;
            default:
                return null;
        }

        String ocrDir = getOcrModelDir(context);
        return new File(ocrDir, modelDirName);
    }

    public static String getModelInfo(ModelType type) {
        switch (type) {
            case PP_OCRV6_MEDIUM_REC:
                return "PP-OCRv6_medium_rec_onnx\n" +
                       "来源: ModelScope (PaddlePaddle)\n" +
                       "URL: https://modelscope.cn/models/PaddlePaddle/PP-OCRv6_medium_rec_onnx\n" +
                       "文件: inference.onnx, inference.yml\n" +
                       "用途: 文字识别 (支持50种语言)\n" +
                       "大小: ~76MB";
            case PP_OCRV5:
                return "PP-OCRv5\n" +
                       "来源: HuggingFace (PaddlePaddle)\n" +
                       "URL: https://huggingface.co/PaddlePaddle/PP-OCRv5\n" +
                       "文件: det/rec/cls 推理文件\n" +
                       "用途: 完整OCR流水线 (检测+识别+分类)\n" +
                       "大小: ~100MB";
            case PADDLE_OCR_VL:
                return "PaddleOCR-VL-1.6\n" +
                       "来源: HuggingFace (PaddlePaddle)\n" +
                       "URL: https://huggingface.co/PaddlePaddle/PaddleOCR-VL-1.6\n" +
                       "文件: model.safetensors, config.json, tokenizer.json\n" +
                       "用途: 视觉语言OCR模型\n" +
                       "大小: ~500MB";
            default:
                return "未知模型";
        }
    }

    public static Map<ModelType, String> getAllModelInfo() {
        Map<ModelType, String> info = new HashMap<>();
        for (ModelType type : ModelType.values()) {
            info.put(type, getModelInfo(type));
        }
        return info;
    }

    public static String exportModelDownloadGuide(Context context) {
        StringBuilder sb = new StringBuilder();
        sb.append("# EhViewer OCR模型下载指南\n\n");
        sb.append("## 模型目录结构\n");
        sb.append("OCR模型: ").append(getOcrModelDir(context)).append("\n");
        sb.append("AI模型: ").append(getAiModelDir(context)).append("\n\n");
        
        sb.append("## 可用模型\n\n");
        for (ModelType type : ModelType.values()) {
            sb.append("### ").append(type.name()).append("\n");
            sb.append(getModelInfo(type)).append("\n\n");
            
            File modelDir = getModelDir(context, type);
            sb.append("本地路径: ").append(modelDir.getAbsolutePath()).append("\n");
            sb.append("状态: ").append(isModelDownloaded(context, type) ? "已下载" : "未下载").append("\n\n");
        }
        
        sb.append("## 手动下载说明\n");
        sb.append("1. 从上述URL下载模型文件\n");
        sb.append("2. 将文件放置到对应的本地路径目录\n");
        sb.append("3. 重启应用或重新初始化OCR服务\n");
        
        return sb.toString();
    }

    private static class DownloadTask extends AsyncTask<Void, String, File> {
        private final Context context;
        private final String baseUrl;
        private final String[] files;
        private final String downloadDir;
        private final String modelDirName;
        private final DownloadCallback callback;
        private String error;

        DownloadTask(Context context, String baseUrl, String[] files, String downloadDir, 
                    String modelDirName, DownloadCallback callback) {
            this.context = context;
            this.baseUrl = baseUrl;
            this.files = files;
            this.downloadDir = downloadDir;
            this.modelDirName = modelDirName;
            this.callback = callback;
        }

        @Override
        protected File doInBackground(Void... voids) {
            File baseDir = new File(downloadDir);
            if (!baseDir.exists()) {
                baseDir.mkdirs();
            }
            
            File modelDir = new File(baseDir, modelDirName);
            if (!modelDir.exists()) {
                modelDir.mkdirs();
            }

            int totalFiles = files.length;
            int completedFiles = 0;

            for (String file : files) {
                String url = baseUrl + file;
                File outputFile = new File(modelDir, file.replace("/", "_"));

                if (outputFile.exists()) {
                    completedFiles++;
                    continue;
                }

                publishProgress("下载: " + file, String.valueOf((completedFiles * 100) / totalFiles));

                try {
                    if (!downloadFile(url, outputFile)) {
                        error = "下载失败: " + file;
                        return null;
                    }
                    completedFiles++;
                } catch (IOException e) {
                    Log.e(TAG, "Download failed: " + file, e);
                    error = "下载失败: " + e.getMessage();
                    return null;
                }
            }

            return modelDir;
        }

        private boolean downloadFile(String urlStr, File outputFile) throws IOException {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");

            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "HTTP error: " + responseCode + " for " + urlStr);
                return false;
            }

            InputStream inputStream = conn.getInputStream();
            FileOutputStream outputStream = new FileOutputStream(outputFile);

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }

            outputStream.flush();
            outputStream.close();
            inputStream.close();

            return true;
        }

        @Override
        protected void onProgressUpdate(String... values) {
            super.onProgressUpdate(values);
            if (callback != null && values.length >= 2) {
                callback.onProgress(Integer.parseInt(values[1]), values[0]);
            }
        }

        @Override
        protected void onPostExecute(File result) {
            super.onPostExecute(result);
            if (callback != null) {
                if (result != null) {
                    callback.onSuccess(result);
                } else {
                    callback.onError(error != null ? error : "未知错误");
                }
            }
        }
    }
}
