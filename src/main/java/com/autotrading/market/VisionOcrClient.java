package com.autotrading.market;

import com.autotrading.config.AiProviderProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Vision (multimodal) OCR client that extracts stock codes from an uploaded
 * image. Uses any OpenAI-compatible Chat Completions endpoint that accepts an
 * {@code image_url} content part (gpt-4o, qwen-vl, gemini via proxy, ...).
 * <p>
 * Returns raw code strings only; {@link com.autotrading.account.MarketInference}
 * decides market/group downstream. The model is told to return strict JSON so
 * parsing is deterministic.
 */
@Service
public class VisionOcrClient {

    private static final Logger log = LoggerFactory.getLogger(VisionOcrClient.class);

    private static final String SYSTEM_PROMPT = """
            你是一个股票代码识别助手。用户会给你一张图片，里面包含若干股票代码
            （可能是美股代码如 AAPL、港股代码如 00700、或 A 股代码如 600519）。
            请只识别股票代码本身，不要包含公司名称、文字说明或换行。
            必须以 JSON 格式返回，结构为：
            {"items":[{"code":"AAPL"},{"code":"00700"}]}
            无法识别为股票代码的内容请忽略。不要输出 JSON 以外的任何文字。""";

    private final AiProviderProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    RestTemplate restTemplate;
    final SimpleClientHttpRequestFactory requestFactory;

    public VisionOcrClient(AiProviderProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(60000);
        factory.setReadTimeout(60000);
        this.restTemplate = new RestTemplate(factory);
        this.requestFactory = factory;
    }

    /** True when a vision provider with a non-blank api-key is configured. */
    public boolean isAvailable() {
        return properties.resolveVision() != null;
    }

    /**
     * Recognizes stock codes in an image. Returns the raw code strings
     * (upper-cased, trimmed, deduped, order-preserving); market inference is
     * the caller's responsibility.
     *
     * @param imageBytes raw image bytes
     * @param contentType MIME type, e.g. "image/png" or "image/jpeg"
     * @return recognized codes; empty list on failure or no matches
     */
    public List<String> recognize(byte[] imageBytes, String contentType) {
        AiProviderProperties.Provider provider = properties.resolveVision();
        if (provider == null) {
            throw new IllegalStateException("Vision provider not configured (AI_VISION_API_KEY is blank)");
        }
        if (imageBytes == null || imageBytes.length == 0) {
            return List.of();
        }
        String mime = (contentType == null || contentType.isBlank()) ? "image/png" : contentType;
        String dataUrl = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(imageBytes);

        if (provider.getTimeoutMs() > 0) {
            requestFactory.setConnectTimeout(provider.getTimeoutMs());
            requestFactory.setReadTimeout(provider.getTimeoutMs());
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(provider.getApiKey());
        if (provider.getUserAgent() != null && !provider.getUserAgent().isBlank()) {
            headers.set(HttpHeaders.USER_AGENT, provider.getUserAgent());
        }

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", provider.getModel());
        requestBody.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", List.of(
                        Map.of("type", "image_url",
                                "image_url", Map.of("url", dataUrl))
                ))
        ));
        if (provider.isJsonMode()) {
            requestBody.put("response_format", Map.of("type", "json_object"));
        }
        if (provider.getTemperature() != null) {
            requestBody.put("temperature", provider.getTemperature());
        }

        try {
            log.info("Calling vision OCR [{}] for {} bytes", provider.getModel(), imageBytes.length);
            ResponseEntity<String> response = restTemplate.exchange(
                    provider.getApiUrl(), HttpMethod.POST,
                    new HttpEntity<>(requestBody, headers), String.class);
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return parseCodes(response.getBody());
            }
            log.warn("Vision OCR returned status {}", response.getStatusCode());
            return List.of();
        } catch (Exception e) {
            log.error("Vision OCR failed: {}", e.getMessage());
            throw new RuntimeException("Vision OCR failed: " + e.getMessage(), e);
        }
    }

    private List<String> parseCodes(String body) {
        List<String> codes = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                log.warn("Vision OCR: no choices in response body");
                return codes;
            }
            String content = choices.get(0).path("message").path("content").asText("");
            JsonNode items = objectMapper.readTree(LlmAnalysisClient.extractJson(content)).path("items");
            if (items.isArray()) {
                for (JsonNode it : items) {
                    String code = it.path("code").asText("").trim().toUpperCase();
                    if (!code.isEmpty() && !codes.contains(code)) {
                        codes.add(code);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse vision OCR response: {}", e.getMessage());
        }
        return codes;
    }
}
