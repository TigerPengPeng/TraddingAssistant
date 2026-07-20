package com.autotrading.market;

import com.autotrading.config.AiProviderProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VisionOcrClientTest {

    private AiProviderProperties props;
    private RestTemplate restTemplate;
    private VisionOcrClient client;

    @BeforeEach
    void setUp() {
        props = new AiProviderProperties();
        AiProviderProperties.Provider vision = new AiProviderProperties.Provider();
        vision.setLabel("Vision");
        vision.setApiKey("vk-123");
        vision.setApiUrl("https://api.openai.com/v1/chat/completions");
        vision.setModel("gpt-4o");
        props.setVision(vision);

        restTemplate = mock(RestTemplate.class);
        client = new VisionOcrClient(props);
        client.restTemplate = restTemplate;
    }

    @Test
    void isAvailableTrueWhenKeyConfigured() {
        assertTrue(client.isAvailable());
    }

    @Test
    void isAvailableFalseWhenKeyBlank() {
        props.getVision().setApiKey("");
        assertFalse(client.isAvailable());
    }

    @Test
    void parsesValidJsonResponse() {
        String body = """
                {"choices":[{"message":{"content":"{\\\"items\\\":[{\\\"code\\\":\\\"AAPL\\\"},{\\\"code\\\":\\\"00700\\\"},{\\\"code\\\":\\\"600519\\\"}]}"}}]}""";
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        List<String> codes = client.recognize(new byte[]{1, 2, 3}, "image/png");

        assertEquals(List.of("AAPL", "00700", "600519"), codes);
    }

    @Test
    void toleratesFencedJsonInContent() {
        String body = """
                {"choices":[{"message":{"content":"```json\\n{\\\"items\\\":[{\\\"code\\\":\\\"MSFT\\\"}]}\\n```"}}]}""";
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        List<String> codes = client.recognize(new byte[]{1}, "image/png");
        assertEquals(List.of("MSFT"), codes);
    }

    @Test
    void dedupesAndUpperCases() {
        String body = """
                {"choices":[{"message":{"content":"{\\\"items\\\":[{\\\"code\\\":\\\"aapl\\\"},{\\\"code\\\":\\\"AAPL\\\"}]}"}}]}""";
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        List<String> codes = client.recognize(new byte[]{1}, "image/png");
        assertEquals(List.of("AAPL"), codes);
    }

    @Test
    void emptyItemsReturnsEmptyList() {
        String body = """
                {"choices":[{"message":{"content":"{\\\"items\\\":[]}"}}]}""";
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        List<String> codes = client.recognize(new byte[]{1}, "image/png");
        assertTrue(codes.isEmpty());
    }

    @Test
    void malformedContentReturnsEmptyNotThrows() {
        String body = """
                {"choices":[{"message":{"content":"sorry I cannot read this image"}}]}""";
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        List<String> codes = client.recognize(new byte[]{1}, "image/png");
        assertTrue(codes.isEmpty());
    }

    @Test
    void missingKeyThrows() {
        props.getVision().setApiKey("");
        assertThrows(IllegalStateException.class,
                () -> client.recognize(new byte[]{1}, "image/png"));
    }

    @Test
    void restClientErrorPropagates() {
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RuntimeException("503 service unavailable"));

        assertThrows(RuntimeException.class,
                () -> client.recognize(new byte[]{1}, "image/png"));
    }
}
