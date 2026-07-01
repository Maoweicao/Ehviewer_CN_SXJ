package com.hippo.ehviewer.lab.translate;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;

import com.hippo.ehviewer.Settings;

public class ImageDetectHelper {
    private static final String TAG = "ImageDetectHelper";

    public static class DetectResult {
        public boolean hasText;
        public float brightness;
        public Rect contentRect;
    }

    public static DetectResult detect(Bitmap image) {
        DetectResult result = new DetectResult();
        if (image == null || image.getWidth() == 0 || image.getHeight() == 0) {
            return result;
        }

        int w = image.getWidth();
        int h = image.getHeight();
        int sampleStep = Math.max(1, Math.min(w, h) / 50);
        int edgeLeft = 0, edgeTop = 0, edgeRight = w, edgeBottom = h;

        int[] pixels = new int[w * h];
        image.getPixels(pixels, 0, w, 0, 0, w, h);

        double totalLuminance = 0;
        int pixelCount = 0;
        double edgeVariance = 0;
        double centerVariance = 0;
        int sampleCount = 0;

        int marginThreshold = Math.min(w, h) / 8;

        for (int y = 0; y < h; y += sampleStep) {
            for (int x = 0; x < w; x += sampleStep) {
                int pixel = pixels[y * w + x];
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;
                double luminance = 0.299 * r + 0.587 * g + 0.114 * b;
                totalLuminance += luminance;
                pixelCount++;

                if (x < marginThreshold || x > w - marginThreshold ||
                        y < marginThreshold || y > h - marginThreshold) {
                    edgeVariance += luminance;
                    sampleCount++;
                } else {
                    centerVariance += luminance;
                }
            }
        }

        if (pixelCount > 0) {
            result.brightness = (float) (totalLuminance / pixelCount / 255.0);
        }

        if (pixelCount > 0 && sampleCount > 0) {
            double avgEdge = edgeVariance / sampleCount;
            double avgCenter = centerVariance / (pixelCount - sampleCount);
            double diff = Math.abs(avgCenter - avgEdge);
            result.hasText = diff > 15 || result.brightness < 0.2 || result.brightness > 0.8;
        }

        result.contentRect = new Rect(0, 0, w, h);

        if (Settings.getAiTranslateAutoDetect()) {
            double contentThreshold = totalLuminance / (pixelCount > 0 ? pixelCount : 1) * 0.3;
            int steps = 20;
            for (int y = 0; y < h; y += h / steps) {
                boolean isMargin = true;
                for (int x = 0; x < w; x += sampleStep) {
                    int pixel = pixels[y * w + x];
                    int r = (pixel >> 16) & 0xFF;
                    int g = (pixel >> 8) & 0xFF;
                    int b = pixel & 0xFF;
                    double val = 0.299 * r + 0.587 * g + 0.114 * b;
                    if (val > contentThreshold) {
                        isMargin = false;
                        break;
                    }
                }
                if (!isMargin) {
                    edgeTop = y;
                    break;
                }
            }
            for (int y = h - 1; y >= 0; y -= h / steps) {
                boolean isMargin = true;
                for (int x = 0; x < w; x += sampleStep) {
                    int pixel = pixels[y * w + x];
                    int r = (pixel >> 16) & 0xFF;
                    int g = (pixel >> 8) & 0xFF;
                    int b = pixel & 0xFF;
                    double val = 0.299 * r + 0.587 * g + 0.114 * b;
                    if (val > contentThreshold) {
                        isMargin = false;
                        break;
                    }
                }
                if (!isMargin) {
                    edgeBottom = y;
                    break;
                }
            }
            result.contentRect = new Rect(edgeLeft, edgeTop, edgeRight, edgeBottom);
        }

        return result;
    }
}
