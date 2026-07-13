package com.autotrading.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for DeepSeek AI API and right-trend analysis.
 */
@Configuration
@ConfigurationProperties(prefix = "deepseek")
public class DeepSeekProperties {

    private Api api = new Api();

    public Api getApi() { return api; }
    public void setApi(Api api) { this.api = api; }

    public static class Api {
        private String key = "";
        private String url = "https://api.deepseek.com/v1/chat/completions";
        private String model = "deepseek-chat";
        private int timeoutMs = 60000;
        private long rateLimitMs = 500;

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public long getRateLimitMs() { return rateLimitMs; }
        public void setRateLimitMs(long rateLimitMs) { this.rateLimitMs = rateLimitMs; }
    }
}
