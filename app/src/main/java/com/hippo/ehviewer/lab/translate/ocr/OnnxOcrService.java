package com.hippo.ehviewer.lab.translate.ocr;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;

import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.lab.translate.model.TranslateResult;

import java.io.File;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

public class OnnxOcrService implements OcrService {
    private static final String TAG = "OnnxOcrService";
    private boolean initialized = false;
    private final ExecutorService executor;
    private OrtSession ortSession;
    private OrtEnvironment ortEnvironment;
    private File modelDir;
    private String inputName;

    public OnnxOcrService() {
        executor = Executors.newSingleThreadExecutor();
    }

    @Override
    public void recognize(Bitmap image, OcrCallback callback) {
        if (!initialized || ortSession == null) {
            callback.onError("ONNX model not initialized");
            return;
        }

        executor.execute(() -> {
            try {
                List<TranslateResult.TextRegion> regions = new ArrayList<>();

                if (image != null) {
                    // 预处理图像
                    float[] inputData = preprocessImage(image);
                    
                    // 创建输入张量
                    long[] inputShape = {1, 3, image.getHeight(), image.getWidth()};
                    FloatBuffer inputBuffer = FloatBuffer.wrap(inputData);
                    OnnxTensor inputTensor = OnnxTensor.createTensor(ortEnvironment, inputBuffer, inputShape);
                    
                    // 运行推理
                    Map<String, OnnxTensor> inputs = Collections.singletonMap(inputName, inputTensor);
                    OrtSession.Result result = ortSession.run(inputs);
                    
                    // 处理输出
                    regions = processOutput(result, image.getWidth(), image.getHeight());
                    
                    // 释放资源
                    inputTensor.close();
                    result.close();
                }

                callback.onSuccess(regions);
            } catch (Exception e) {
                Log.e(TAG, "ONNX inference failed", e);
                callback.onError(e.getMessage());
            }
        });
    }

    private float[] preprocessImage(Bitmap image) {
        int width = image.getWidth();
        int height = image.getHeight();
        float[] inputData = new float[3 * height * width];
        
        // 归一化像素值到 [0, 1] 并转换为 CHW 格式
        int[] pixels = new int[width * height];
        image.getPixels(pixels, 0, width, 0, 0, width, height);
        
        for (int i = 0; i < height; i++) {
            for (int j = 0; j < width; j++) {
                int pixel = pixels[i * width + j];
                // RGB channels
                inputData[0 * height * width + i * width + j] = ((pixel >> 16) & 0xFF) / 255.0f; // R
                inputData[1 * height * width + i * width + j] = ((pixel >> 8) & 0xFF) / 255.0f;  // G
                inputData[2 * height * width + i * width + j] = (pixel & 0xFF) / 255.0f;         // B
            }
        }
        
        return inputData;
    }

    private List<TranslateResult.TextRegion> processOutput(OrtSession.Result result, int imageWidth, int imageHeight) {
        List<TranslateResult.TextRegion> regions = new ArrayList<>();
        
        try {
            // 获取输出张量
            Object output = result.get(0).getValue();
            
            if (output instanceof float[][]) {
                float[][] outputArray = (float[][]) output;
                // 处理识别结果
                // 这里需要根据具体的模型输出格式来解析
                // PP-OCRv6 输出格式可能是 [batch, seq_len, vocab_size]
                StringBuilder recognizedText = new StringBuilder();
                float confidence = 0.9f;
                
                for (float[] row : outputArray) {
                    // 简单的贪心解码
                    int maxIdx = 0;
                    float maxVal = row[0];
                    for (int i = 1; i < row.length; i++) {
                        if (row[i] > maxVal) {
                            maxVal = row[i];
                            maxIdx = i;
                        }
                    }
                    // 这里需要字符映射表来将索引转换为字符
                    // 暂时使用占位符
                    if (maxIdx > 0) {
                        recognizedText.append((char) (maxIdx + 32)); // 简单映射
                    }
                }
                
                if (recognizedText.length() > 0) {
                    Rect rect = new Rect(0, 0, imageWidth, imageHeight / 4);
                    TranslateResult.TextRegion region = new TranslateResult.TextRegion(
                            rect, recognizedText.toString(), "");
                    region.confidence = confidence;
                    regions.add(region);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing output", e);
        }
        
        return regions;
    }

    @Override
    public boolean isAvailable() {
        if (Settings.getAiOcrUseCustomPath()) {
            String modelPath = Settings.getAiOcrFullModelPath();
            if (modelPath != null && !modelPath.isEmpty()) {
                File modelFile = new File(modelPath);
                return modelFile.exists() && modelFile.getName().toLowerCase().endsWith(".onnx");
            }
        }
        
        // 检查默认模型目录
        String selectedModel = Settings.getAiOcrSelectedModel();
        File modelDir = getModelDirectory(selectedModel);
        if (modelDir != null && modelDir.exists()) {
            File[] files = modelDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.getName().toLowerCase().endsWith(".onnx")) {
                        return true;
                    }
                }
            }
        }
        
        return false;
    }

    private File getModelDirectory(String modelName) {
        try {
            OcrModelDownloader.ModelType type = OcrModelDownloader.ModelType.valueOf(modelName);
            return OcrModelDownloader.getModelDir(null, type);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String getEngineName() {
        return "ONNX Runtime";
    }

    @Override
    public void initialize() {
        try {
            String modelPath = getModelPath();
            if (modelPath == null || modelPath.isEmpty()) {
                Log.w(TAG, "Model path not configured");
                return;
            }

            File modelFile = new File(modelPath);
            if (!modelFile.exists()) {
                Log.w(TAG, "Model file not found: " + modelPath);
                return;
            }

            // 初始化 ONNX Runtime
            ortEnvironment = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            
            ortSession = ortEnvironment.createSession(modelPath, options);
            modelDir = modelFile.getParentFile();
            
            // 获取模型输入名称（通常只有一个输入）
            try {
                inputName = ortSession.getInputNames().iterator().next();
            } catch (Exception e) {
                inputName = "input";
            }
            initialized = true;
            
            Log.d(TAG, "ONNX model initialized from: " + modelPath);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize ONNX model", e);
        }
    }

    private String getModelPath() {
        if (Settings.getAiOcrUseCustomPath()) {
            return Settings.getAiOcrFullModelPath();
        }
        
        // 根据选择的模型类型获取路径
        String selectedModel = Settings.getAiOcrSelectedModel();
        try {
            OcrModelDownloader.ModelType type = OcrModelDownloader.ModelType.valueOf(selectedModel);
            File modelDir = OcrModelDownloader.getModelDir(null, type);
            if (modelDir != null && modelDir.exists()) {
                File[] files = modelDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.getName().toLowerCase().endsWith(".onnx")) {
                            return file.getAbsolutePath();
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting model path", e);
        }
        
        return null;
    }

    @Override
    public void release() {
        initialized = false;
        try {
            if (ortSession != null) {
                ortSession.close();
                ortSession = null;
            }
            if (ortEnvironment != null) {
                ortEnvironment.close();
                ortEnvironment = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error releasing ONNX session", e);
        }
        executor.shutdown();
    }
}
