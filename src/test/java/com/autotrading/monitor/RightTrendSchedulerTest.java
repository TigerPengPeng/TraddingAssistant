package com.autotrading.monitor;

import com.autotrading.config.RightTrendProperties;
import com.autotrading.market.RightTrendAnalysisService;
import com.autotrading.market.RightTrendAnalysisService.RightTrendReport;
import com.autotrading.notification.EmailNotificationService;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies the daily scheduled jobs use the dedicated scheduled-provider
 * (default deepseek) rather than the frontend's ai.default-provider.
 */
class RightTrendSchedulerTest {

    private RightTrendAnalysisService analysisService;
    private EmailNotificationService emailService;
    private RightTrendProperties properties;
    private RightTrendScheduler scheduler;

    @BeforeEach
    void setUp() {
        analysisService = mock(RightTrendAnalysisService.class);
        emailService = mock(EmailNotificationService.class);
        properties = new RightTrendProperties();
        scheduler = new RightTrendScheduler(analysisService, emailService, properties);

        when(analysisService.analyzeGroups(anyList(), anyString()))
                .thenReturn(new RightTrendReport("2026-07-20", List.of(), List.of(),
                        System.currentTimeMillis(), "deepseek", "DeepSeek"));
    }

    @Test
    @DisplayName("US scheduled job uses right-trend.scheduled-provider (default deepseek)")
    void usJobUsesScheduledProvider() {
        scheduler.analyzeUS();

        ArgumentCaptor<String> provider = ArgumentCaptor.forClass(String.class);
        verify(analysisService).analyzeGroups(eq(List.of("US")), provider.capture());
        assertEquals("deepseek", provider.getValue());
    }

    @Test
    @DisplayName("HK+CN scheduled job honors a configured scheduled-provider override")
    void hkCnJobUsesConfiguredProvider() {
        properties.setScheduledProvider("kimi");

        scheduler.analyzeHKAndCN();

        ArgumentCaptor<String> provider = ArgumentCaptor.forClass(String.class);
        verify(analysisService).analyzeGroups(eq(List.of("HK", "CN")), provider.capture());
        assertEquals("kimi", provider.getValue());
    }
}
