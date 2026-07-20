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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM client for right-side trend analysis. Works with any OpenAI-compatible
 * Chat Completions endpoint (DeepSeek, GLM, Kimi/Moonshot, ...). The provider
 * (url/key/model/json-mode) is chosen per call via AiProviderProperties.
 */
@Service
public class LlmAnalysisClient {

    private static final Logger log = LoggerFactory.getLogger(LlmAnalysisClient.class);

    private static final String SYSTEM_PROMPT = """
            你是一位专业的股票趋势分析师，擅长通过K线数据判断股票是否进入"右侧趋势"。
            右侧趋势的定义：股票经过一段下跌趋势后，价格触底企稳，开始形成上升趋势。
            主要特征：价格突破关键均线并站稳、形成更高的低点和更高的高点、下跌缩量上涨放量。
            仅处于反弹但尚未确认趋势反转的，不应判定为进入右侧趋势。
            请以JSON格式返回分析结果。""";

    private final AiProviderProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Package-private for test injection.
    RestTemplate restTemplate;

    public LlmAnalysisClient(AiProviderProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeout = resolveDefaultTimeoutMs(properties);
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.restTemplate = new RestTemplate(factory);
    }

    private int resolveDefaultTimeoutMs(AiProviderProperties props) {
        AiProviderProperties.Resolved resolved = props.resolve(props.getDefaultProvider());
        if (resolved != null && resolved.provider().getTimeoutMs() > 0) {
            return resolved.provider().getTimeoutMs();
        }
        return 60000;
    }

    /**
     * Analyzes a single stock for right-side trend entry using the given provider.
     *
     * @param stockName  display name
     * @param stockKey   market.code key
     * @param marketLabel human-readable market (美股/港股/A股)
     * @param klines     K-line data (most recent bars)
     * @param provider   resolved LLM provider (url/key/model/json-mode)
     * @return LlmAnalysis result
     */
    public LlmAnalysis analyzeRightTrend(String stockName, String stockKey,
                                          String marketLabel,
                                          List<KLineService.KLineData> klines,
                                          AiProviderProperties.Provider provider) {
        if (klines.isEmpty()) {
            return LlmAnalysis.failed("No K-line data available");
        }

        String userMessage = buildUserMessage(stockName, stockKey, marketLabel, klines);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(provider.getApiKey());
            // Some providers (Kimi /coding endpoint) require a specific User-Agent
            // to identify the client type. Only set when non-blank.
            if (provider.getUserAgent() != null && !provider.getUserAgent().isBlank()) {
                headers.set(HttpHeaders.USER_AGENT, provider.getUserAgent());
            }

            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", provider.getModel());
            requestBody.put("messages", List.of(
                    Map.of("role", "system", "content", SYSTEM_PROMPT),
                    Map.of("role", "user", "content", userMessage)
            ));
            // Only send response_format when the provider supports JSON mode
            // (some endpoints, e.g. early Moonshot, reject the field).
            if (provider.isJsonMode()) {
                requestBody.put("response_format", Map.of("type", "json_object"));
            }
            requestBody.put("temperature", 0.1);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            log.info("Calling LLM [{}] for {} ({})", provider.getModel(), stockName, stockKey);
            ResponseEntity<String> response = restTemplate.exchange(
                    provider.getApiUrl(),
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return parseResponse(response.getBody(), stockKey);
            } else {
                return LlmAnalysis.failed("API returned status: " + response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("LLM analysis failed for {}: {}", stockKey, e.getMessage());
            return LlmAnalysis.failed("LLM API error: " + e.getMessage());
        }
    }

    private String buildUserMessage(String stockName, String stockKey, String marketLabel,
                                      List<KLineService.KLineData> klines) {
        StringBuilder sb = new StringBuilder();
        sb.append("股票：").append(stockName)
          .append("(").append(stockKey).append(")  市场：").append(marketLabel).append("\n");
        sb.append("日期,开盘,最高,最低,收盘,涨跌幅%,成交量\n");

        // Take last 60 bars
        int fromIndex = Math.max(0, klines.size() - 60);
        for (int i = fromIndex; i < klines.size(); i++) {
            KLineService.KLineData k = klines.get(i);
            sb.append(String.format("%s,%.4f,%.4f,%.4f,%.4f,%.2f,%d%n",
                    k.time(), k.open(), k.high(), k.low(), k.close(), k.changeRate(), k.volume()));
        }

        sb.append("\n请分析以上K线数据，判断该股票是否已进入右侧趋势。");
        sb.append("请以JSON格式返回，字段如下：\n");
        sb.append("- isInRightTrend: 布尔值，是否已进入右侧趋势\n");
        sb.append("- confidence: 字符串，置信度(high/medium/low)\n");
        sb.append("- trendDirection: 字符串，趋势方向(up/down/sideways)\n");
        sb.append("- keySignals: 字符串数组，关键信号列表\n");
        sb.append("- reason: 字符串，详细分析原因");

        return sb.toString();
    }

    private LlmAnalysis parseResponse(String body, String stockKey) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return LlmAnalysis.failed("No choices in response");
            }

            String content = choices.get(0).path("message").path("content").asText();
            JsonNode analysis = objectMapper.readTree(extractJson(content));

            boolean isInRightTrend = analysis.path("isInRightTrend").asBoolean(false);
            String confidence = analysis.path("confidence").asText("low");
            String trendDirection = analysis.path("trendDirection").asText("sideways");
            String reason = analysis.path("reason").asText("");

            List<String> keySignals = new ArrayList<>();
            JsonNode signalsNode = analysis.path("keySignals");
            if (signalsNode.isArray()) {
                for (JsonNode s : signalsNode) {
                    keySignals.add(s.asText());
                }
            }

            return new LlmAnalysis(true, isInRightTrend, confidence, trendDirection,
                    keySignals, reason, null);
        } catch (Exception e) {
            log.error("Failed to parse LLM response for {}: {}", stockKey, e.getMessage());
            return LlmAnalysis.failed("Failed to parse response: " + e.getMessage());
        }
    }

    /**
     * Extracts a JSON object from possibly-fenced or prose-wrapped model output.
     * Handles ```json ... ```, ``` ... ```, and raw text with surrounding prose.
     */
    static String extractJson(String content) {
        if (content == null || content.isBlank()) {
            return "{}";
        }
        String s = content.trim();
        // Strip a single layer of ```...``` or ```json...``` fences.
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline > 0) {
                s = s.substring(firstNewline + 1);
            }
            int lastFence = s.lastIndexOf("```");
            if (lastFence >= 0) {
                s = s.substring(0, lastFence);
            }
            s = s.trim();
        }
        // Slice to the outermost JSON object as a fallback.
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return s.substring(start, end + 1);
        }
        return s;
    }

    /** Result of a single LLM analysis. */
    public record LlmAnalysis(boolean success, boolean isInRightTrend, String confidence,
                                String trendDirection, List<String> keySignals,
                                String reason, String error) {

        static LlmAnalysis failed(String error) {
            return new LlmAnalysis(false, false, "low", "unknown",
                    List.of(), "", error);
        }
    }
}
