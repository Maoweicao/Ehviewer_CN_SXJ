package com.hippo.ehviewer.lab.translate;

import java.util.LinkedHashMap;
import java.util.Map;

public class TranslatePresets {

    public static class ProviderPreset {
        public final String id;
        public final String name;
        public final String defaultBaseUrl;
        public final boolean needsApiKey;
        public final String defaultModel;

        public ProviderPreset(String id, String name, String defaultBaseUrl, boolean needsApiKey, String defaultModel) {
            this.id = id;
            this.name = name;
            this.defaultBaseUrl = defaultBaseUrl;
            this.needsApiKey = needsApiKey;
            this.defaultModel = defaultModel;
        }
    }

    public static final Map<String, ProviderPreset> PROVIDERS = new LinkedHashMap<>();
    public static final String DEFAULT_PROVIDER = "custom";

    static {
        PROVIDERS.put("ollama", new ProviderPreset(
                "ollama", "Ollama (Local)",
                "http://localhost:11434/v1",
                false,
                "gemma2:12b"
        ));
        PROVIDERS.put("llamacpp", new ProviderPreset(
                "llamacpp", "llama.cpp Server",
                "http://localhost:8080/v1",
                false,
                "default"
        ));
        PROVIDERS.put("unsloth", new ProviderPreset(
                "unsloth", "Unsloth",
                "https://api.unsloth.ai/v1",
                true,
                "unsloth/Qwen3-32B"
        ));
        PROVIDERS.put("deepseek", new ProviderPreset(
                "deepseek", "DeepSeek",
                "https://api.deepseek.com/v1",
                true,
                "deepseek-v4-flash"
        ));
        PROVIDERS.put("openai", new ProviderPreset(
                "openai", "OpenAI",
                "https://api.openai.com/v1",
                true,
                "gpt-4o-mini"
        ));
        PROVIDERS.put("custom", new ProviderPreset(
                "custom", "Custom API",
                "",
                false,
                ""
        ));
    }

    public static ProviderPreset getPreset(String providerId) {
        ProviderPreset preset = PROVIDERS.get(providerId);
        if (preset == null) {
            preset = PROVIDERS.get(DEFAULT_PROVIDER);
        }
        return preset;
    }

    public static String getDefaultBaseUrl(String providerId) {
        ProviderPreset p = PROVIDERS.get(providerId);
        return p != null ? p.defaultBaseUrl : "";
    }

    public static boolean needsApiKey(String providerId) {
        ProviderPreset p = PROVIDERS.get(providerId);
        return p != null && p.needsApiKey;
    }
}
