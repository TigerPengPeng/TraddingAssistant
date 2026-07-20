package com.autotrading.market;

import com.autotrading.account.StockGroupService;
import com.autotrading.config.AiProviderProperties;
import com.autotrading.config.RightTrendProperties;
import com.autotrading.futu.AsyncRequestBridge;
import com.autotrading.market.LlmAnalysisClient.LlmAnalysis;
import com.autotrading.market.RightTrendAnalysisService.RightTrendReport;
import com.autotrading.market.RightTrendAnalysisService.StockTrendResult;
import com.autotrading.model.StockInfo;
import com.autotrading.repository.RightTrendAnalysisRecordRepository;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for RightTrendAnalysisService orchestration logic.
 */
class RightTrendAnalysisServiceTest {

    private StockGroupService stockGroupService;
    private KLineService kLineService;
    private LlmAnalysisClient llmClient;
    private AiProviderProperties aiProps;
    private RightTrendProperties rightTrendProps;
    private RightTrendAnalysisRecordRepository repository;
    private RightTrendAnalysisService service;

    @BeforeEach
    void setUp() throws Exception {
        stockGroupService = mock(StockGroupService.class);
        kLineService = mock(KLineService.class);
        llmClient = mock(LlmAnalysisClient.class);
        aiProps = new AiProviderProperties();
        // Configure a single provider with no rate limit so tests run fast.
        AiProviderProperties.Provider deepseek = new AiProviderProperties.Provider();
        deepseek.setLabel("DeepSeek");
        deepseek.setApiKey("test-key");
        deepseek.setRateLimitMs(0);
        aiProps.getProviders().put("deepseek", deepseek);
        rightTrendProps = new RightTrendProperties();
        rightTrendProps.setKlineLookback(60);
        repository = mock(RightTrendAnalysisRecordRepository.class);

        service = new RightTrendAnalysisService(
                stockGroupService, kLineService, llmClient,
                aiProps, rightTrendProps, repository);
    }

    private List<KLineService.KLineData> sampleKLines() {
        return List.of(new KLineService.KLineData("2025-01-01", 99, 101, 98, 100, 1000L, 1.0));
    }

    @Test
    @DisplayName("Analyzes a single group end-to-end")
    void analyzesSingleGroup() throws Exception {
        StockInfo stock1 = new StockInfo(2, "00700", "腾讯");
        StockInfo stock2 = new StockInfo(2, "09988", "阿里");

        when(stockGroupService.getStocksInGroup("港股")).thenReturn(List.of(stock1, stock2));
        when(kLineService.getOrFetchKLines(any(StockInfo.class))).thenReturn(sampleKLines());
        when(llmClient.analyzeRightTrend(anyString(), anyString(), anyString(), anyList(), any()))
                .thenReturn(new LlmAnalysis(true, true, "high", "up",
                        List.of("突破MA30"), "confirmed uptrend", null));

        RightTrendReport report = service.analyzeGroup("港股");

        assertEquals(2, report.stocks().size());
        assertTrue(report.stocks().stream().allMatch(StockTrendResult::isInRightTrend));
        verify(repository, times(2)).save(any());
    }

    @Test
    @DisplayName("Continues when a single stock analysis fails")
    void continuesOnSingleFailure() throws Exception {
        StockInfo stock1 = new StockInfo(11, "AAPL", "Apple");
        StockInfo stock2 = new StockInfo(11, "MSFT", "Microsoft");

        when(stockGroupService.getStocksInGroup("美股")).thenReturn(List.of(stock1, stock2));
        when(kLineService.getOrFetchKLines(any(StockInfo.class))).thenReturn(sampleKLines());
        when(llmClient.analyzeRightTrend(eq("Apple"), anyString(), anyString(), anyList(), any()))
                .thenReturn(LlmAnalysis.failed("API timeout"));
        when(llmClient.analyzeRightTrend(eq("Microsoft"), anyString(), anyString(), anyList(), any()))
                .thenReturn(new LlmAnalysis(true, true, "medium", "up",
                        List.of("higher lows"), "trending up", null));

        RightTrendReport report = service.analyzeGroup("美股");

        assertEquals(2, report.stocks().size());
        assertFalse(report.stocks().get(0).success());
        assertTrue(report.stocks().get(1).success());
    }

    @Test
    @DisplayName("Handles group fetch failure gracefully")
    void handlesGroupFetchError() throws Exception {
        when(stockGroupService.getStocksInGroup("港股"))
                .thenThrow(new AsyncRequestBridge.FutuRequestException("Not connected"));

        RightTrendReport report = service.analyzeGroup("港股");

        assertEquals(0, report.stocks().size());
    }

    @Test
    @DisplayName("Handles K-line fetch failure per stock")
    void handlesKlineFetchError() throws Exception {
        StockInfo stock1 = new StockInfo(11, "AAPL", "Apple");

        when(stockGroupService.getStocksInGroup("美股")).thenReturn(List.of(stock1));
        when(kLineService.getOrFetchKLines(any(StockInfo.class)))
                .thenReturn(List.of()); // cache miss + API quota exhausted

        RightTrendReport report = service.analyzeGroup("美股");

        assertEquals(1, report.stocks().size());
        assertFalse(report.stocks().get(0).success());
        verifyNoInteractions(llmClient);
    }
}
