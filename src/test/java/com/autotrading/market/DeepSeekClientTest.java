package com.autotrading.market;

import com.autotrading.config.DeepSeekProperties;
import com.autotrading.market.DeepSeekClient.DeepSeekAnalysis;
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
import static org.mockito.Mockito.*;

/**
 * Tests for DeepSeekClient JSON parsing and error handling.
 */
class DeepSeekClientTest {

    private DeepSeekProperties props;
    private RestTemplate restTemplate;
    private DeepSeekClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        props = new DeepSeekProperties();
        props.getApi().setKey("test-key");
        props.getApi().setUrl("https://api.deepseek.com/v1/chat/completions");
        props.getApi().setModel("deepseek-chat");
        props.getApi().setTimeoutMs(5000);
        restTemplate = mock(RestTemplate.class);
        client = new DeepSeekClient(props);
        client.restTemplate = restTemplate;
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

        DeepSeekAnalysis result = client.analyzeRightTrend("腾讯", "2.00700", "港股", sampleKLines(60));

        assertTrue(result.success());
        assertTrue(result.isInRightTrend());
        assertEquals("high", result.confidence());
        assertEquals("up", result.trendDirection());
        assertEquals(2, result.keySignals().size());
        assertTrue(result.reason().contains("突破"));
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

        DeepSeekAnalysis result = client.analyzeRightTrend("AAPL", "11.AAPL", "美股", sampleKLines(60));

        assertTrue(result.success());
        assertFalse(result.isInRightTrend());
        assertEquals("medium", result.confidence());
        assertEquals("down", result.trendDirection());
    }

    @Test
    @DisplayName("Handles HTTP error gracefully")
    void handlesHttpError() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RestClientException("Connection refused"));

        DeepSeekAnalysis result = client.analyzeRightTrend("TEST", "11.TEST", "美股", sampleKLines(60));

        assertFalse(result.success());
        assertNotNull(result.error());
        assertTrue(result.error().contains("Connection refused"));
    }

    @Test
    @DisplayName("Handles empty kline data without calling API")
    void handlesEmptyKlines() {
        DeepSeekAnalysis result = client.analyzeRightTrend("TEST", "11.TEST", "美股", List.of());

        assertFalse(result.success());
        assertTrue(result.error().contains("No K-line"));
        verifyNoInteractions(restTemplate);
    }
}
