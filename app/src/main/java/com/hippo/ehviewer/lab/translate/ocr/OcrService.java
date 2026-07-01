package com.hippo.ehviewer.lab.translate.ocr;

import android.graphics.Bitmap;
import android.graphics.Rect;

import com.hippo.ehviewer.lab.translate.model.TranslateResult;

import java.util.List;

public interface OcrService {
    
    interface OcrCallback {
        void onSuccess(List<TranslateResult.TextRegion> regions);
        void onError(String error);
    }

    void recognize(Bitmap image, OcrCallback callback);
    
    boolean isAvailable();
    
    String getEngineName();
    
    void initialize();
    
    void release();
}
