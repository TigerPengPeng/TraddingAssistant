package com.autotrading.market;

import com.autotrading.config.AiProviderProperties;
import com.autotrading.market.LlmAnalysisClient.LlmAnalysis;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;

/**
 * Tests for LlmAnalysisClient: per-provider request building, JSON parsing
 * (including fenced/prose output), and error handling.
 */
class LlmAnalysisClientTest {

    private AiProviderProperties props;
    private RestTemplate restTemplate;
    private LlmAnalysisClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        props = new AiProviderProperties();
        props.setDefaultProvider("deepseek");
        AiProviderProperties.Provider deepseek = provider("DeepSeek",
                "test-key", "https://api.deepseek.com/v1/chat/completions", "deepseek-chat");
        AiProviderProperties.Provider glm = provider("智谱GLM",
                "glm-key", "https://open.bigmodel.cn/api/paas/v4/chat/completions", "glm-4.6");
        AiProviderProperties.Provider kimi = provider("Kimi",
                "kimi-key", "https://api.moonshot.cn/v1/chat/completions", "moonshot-v1-auto");
        kimi.setJsonMode(false); // simulate a provider that rejects response_format
        props.getProviders().put("deepseek", deepseek);
        props.getProviders().put("glm", glm);
        props.getProviders().put("kimi", kimi);

        restTemplate = mock(RestTemplate.class);
        client = new LlmAnalysisClient(props);
        client.restTemplate = restTemplate;
    }

    private AiProviderProperties.Provider provider(String label, String key, String url, String model) {
        AiProviderProperties.Provider p = new AiProviderProperties.Provider();
        p.setLabel(label);
        p.setApiKey(key);
        p.setApiUrl(url);
        p.setModel(model);
        p.setTimeoutMs(5000);
        return p;
    }

    private List<KLineService.KLineData> sampleKLines(int count) {
        List<KLineService.KLineData> klines = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double price = 100 + i * 0.5;
            klines.add(new KLineService.KLineData(
                    "2025-01-" + String.format("%02d", i + 1),
                    price - 0.2, price + 0.3, price - 0.5, price,
                    1000000L, 1.5));
        }
        return klines;
    }

    private String buildApiResponse(String content) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "choices", List.of(Map.of(
                        "message", Map.of("content", content)
                ))
        ));
    }

    @Test
    @DisplayName("Parses successful response with right-trend = true")
    void parsesSuccessTrueResponse() throws Exception {
        String content = objectMapper.writeValueAsString(Map.of(
                "isInRightTrend", true,
                "confidence", "high",
                "trendDirection", "up",
                "keySignals", List.of("突破MA30", "成交量放大"),
                "reason", "价格突破所有均线，形成更高的高低点"
        ));

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>(buildApiResponse(content), HttpStatus.OK));

        AiProviderProperties.Provider p = clientProvider("deepseek");
        LlmAnalysis result = client.analyzeRightTrend("腾讯", "2.00700", "港股", sampleKLines(60), p);

        assertTrue(result.success());
        assertTrue(result.isInRightTrend());
        assertEquals("high", result.confidence());
        assertEquals("up", result.trendDirection());
        assertEquals(2, result.keySignals().size());
        assertTrue(result.reason().contains("突破"));
    }

    @Test
    @DisplayName("Strips ```json fences from model output before parsing")
    void stripsJsonFences() throws Exception {
        String content = "```json\n" + objectMapper.writeValueAsString(Map.of(
                "isInRightTrend", true,
                "confidence", "medium",
                "trendDirection", "up",
                "keySignals", List.of("higher lows"),
                "reason", "trending up"
        )) + "\n```";

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>(buildApiResponse(content), HttpStatus.OK));

        LlmAnalysis result = client.analyzeRightTrend("T", "2.T", "港股", sampleKLines(60), clientProvider("deepseek"));

        assertTrue(result.success());
        assertTrue(result.isInRightTrend());
        assertEquals("medium", result.confidence());
    }

    @Test
    @DisplayName("Parses response with right-trend = false")
    void parsesSuccessFalseResponse() throws Exception {
        String content = objectMapper.writeValueAsString(Map.of(
                "isInRightTrend", false,
                "confidence", "medium",
                "trendDirection", "down",
                "keySignals", List.of("仍在MA30下方"),
                "reason", "价格未突破关键均线"
        ));

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>(buildApiResponse(content), HttpStatus.OK));

        LlmAnalysis result = client.analyzeRightTrend("AAPL", "11.AAPL", "美股", sampleKLines(60), clientProvider("deepseek"));

        assertTrue(result.success());
        assertFalse(result.isInRightTrend());
        assertEquals("medium", result.confidence());
        assertEquals("down", result.trendDirection());
    }

    @Test
    @DisplayName("Sends request to the provider's url/model with bearer key")
    void routesToProviderConfig() throws Exception {
        String content = objectMapper.writeValueAsString(Map.of(
                "isInRightTrend", true, "confidence", "high", "trendDirection", "up",
                "keySignals", List.of(), "reason", "ok"
        ));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>(buildApiResponse(content), HttpStatus.OK));

        client.analyzeRightTrend("T", "2.T", "港股", sampleKLines(60), clientProvider("glm"));

        // Capture the request and assert the GLM endpoint + model are used.
        ArgumentCaptor<HttpEntity<?>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq("https://open.bigmodel.cn/api/paas/v4/chat/completions"),
                eq(HttpMethod.POST), captor.capture(), eq(String.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) captor.getValue().getBody();
        assertEquals("glm-4.6", body.get("model"));
        assertEquals("Bearer glm-key", captor.getValue().getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        assertNotNull(body.get("response_format")); // GLM keeps json mode
    }

    @Test
    @DisplayName("Omits response_format when provider.jsonMode is false (e.g. Kimi)")
    void omitsResponseFormatForKimi() throws Exception {
        String content = objectMapper.writeValueAsString(Map.of(
                "isInRightTrend", true, "confidence", "high", "trendDirection", "up",
                "keySignals", List.of(), "reason", "ok"
        ));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>(buildApiResponse(content), HttpStatus.OK));

        client.analyzeRightTrend("T", "2.T", "港股", sampleKLines(60), clientProvider("kimi"));

        ArgumentCaptor<HttpEntity<?>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq("https://api.moonshot.cn/v1/chat/completions"),
                eq(HttpMethod.POST), captor.capture(), eq(String.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) captor.getValue().getBody();
        assertEquals("moonshot-v1-auto", body.get("model"));
        assertNull(body.get("response_format"));
    }

    @Test
    @DisplayName("Handles HTTP error gracefully")
    void handlesHttpError() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RestClientException("Connection refused"));

        LlmAnalysis result = client.analyzeRightTrend("TEST", "11.TEST", "美股", sampleKLines(60), clientProvider("deepseek"));

        assertFalse(result.success());
        assertNotNull(result.error());
        assertTrue(result.error().contains("Connection refused"));
    }

    @Test
    @DisplayName("Handles empty kline data without calling API")
    void handlesEmptyKlines() {
        LlmAnalysis result = client.analyzeRightTrend("TEST", "11.TEST", "美股", List.of(), clientProvider("deepseek"));

        assertFalse(result.success());
        assertTrue(result.error().contains("No K-line"));
        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("extractJson slices a JSON object out of surrounding prose")
    void extractJsonSlicesObject() {
        assertEquals("{\"a\":1}", LlmAnalysisClient.extractJson("sure, here: {\"a\":1} done"));
        assertEquals("{\"a\":1}", LlmAnalysisClient.extractJson("```json\n{\"a\":1}\n```"));
        assertEquals("{\"a\":1}", LlmAnalysisClient.extractJson("```\n{\"a\":1}\n```"));
        assertEquals("{}", LlmAnalysisClient.extractJson(""));
        assertEquals("{}", LlmAnalysisClient.extractJson(null));
    }

    /** Lookups the provider by id from the client's properties (mirrors resolve()). */
    private AiProviderProperties.Provider clientProvider(String id) {
        return props.getProviders().get(id);
    }
}
