package com.hippo.ehviewer.lab.translate.translate;

public interface TranslateService {
    
    interface TranslateCallback {
        void onSuccess(String translated);
        void onError(String error);
    }

    void translate(String text, String sourceLang, String targetLang, TranslateCallback callback);
    
    boolean isAvailable();
    
    String getProviderName();
    
    void initialize();
    
    void release();
}
