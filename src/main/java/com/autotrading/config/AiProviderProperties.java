package com.autotrading.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of OpenAI-compatible LLM providers (DeepSeek, GLM, Kimi, ...).
 * Replaces the single-provider DeepSeekProperties. Each provider carries its
 * own label/api-key/api-url/model/json-mode/timeout/rate-limit. Only providers
 * with a non-blank api-key are considered "configured" and surfaced to callers.
 */
@Configuration
@ConfigurationProperties(prefix = "ai")
public class AiProviderProperties {

    private String defaultProvider = "deepseek";
    private Map<String, Provider> providers = new LinkedHashMap<>();

    public String getDefaultProvider() { return defaultProvider; }
    public void setDefaultProvider(String defaultProvider) { this.defaultProvider = defaultProvider; }
    public Map<String, Provider> getProviders() { return providers; }
    public void setProviders(Map<String, Provider> providers) { this.providers = providers; }

    /** Returns configured providers (non-blank api-key), in declaration order. */
    public List<Map.Entry<String, Provider>> getConfiguredProviders() {
        List<Map.Entry<String, Provider>> result = new ArrayList<>();
        for (Map.Entry<String, Provider> e : providers.entrySet()) {
            if (isConfigured(e.getKey())) {
                result.add(e);
            }
        }
        return result;
    }

    /** True if the id exists and has a non-blank api-key. */
    public boolean isConfigured(String id) {
        Provider p = providers.get(id);
        return p != null && p.getApiKey() != null && !p.getApiKey().isBlank();
    }

    /**
     * Resolves a provider: explicit id if configured, else default if configured,
     * else first configured. Returns null only when nothing is configured.
     */
    public Resolved resolve(String id) {
        if (id != null && isConfigured(id)) {
            return new Resolved(id, providers.get(id));
        }
        if (isConfigured(defaultProvider)) {
            return new Resolved(defaultProvider, providers.get(defaultProvider));
        }
        List<Map.Entry<String, Provider>> configured = getConfiguredProviders();
        if (!configured.isEmpty()) {
            Map.Entry<String, Provider> first = configured.get(0);
            return new Resolved(first.getKey(), first.getValue());
        }
        return null;
    }

    public record Resolved(String id, Provider provider) {}

    public static class Provider {
        private String label = "";
        private String apiKey = "";
        private String apiUrl = "";
        private String model = "";
        private boolean jsonMode = true;
        private int timeoutMs = 60000;
        private long rateLimitMs = 500;

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getApiUrl() { return apiUrl; }
        public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public boolean isJsonMode() { return jsonMode; }
        public void setJsonMode(boolean jsonMode) { this.jsonMode = jsonMode; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public long getRateLimitMs() { return rateLimitMs; }
        public void setRateLimitMs(long rateLimitMs) { this.rateLimitMs = rateLimitMs; }
    }
}
