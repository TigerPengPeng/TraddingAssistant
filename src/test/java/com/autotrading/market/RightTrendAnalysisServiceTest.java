package com.autotrading.market;

import com.autotrading.account.StockGroupService;
import com.autotrading.config.AiProviderProperties;
import com.autotrading.config.RightTrendProperties;
import com.autotrading.futu.AsyncRequestBridge;
import com.autotrading.market.LlmAnalysisClient.LlmAnalysis;
import com.autotrading.market.RightTrendAnalysisService.RightTrendReport;
import com.autotrading.market.RightTrendAnalysisService.StockTrendResult;
import com.autotrading.model.StockInfo;
import com.autotrading.entity.RightTrendAnalysisRecord;
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
        // 用今天日期，避免被 stale 校验误判为过旧
        String today = java.time.LocalDate.now().toString();
        return List.of(new KLineService.KLineData(today + " 00:00:00", 99, 101, 98, 100, 1000L, 1.0));
    }

    @Test
    @DisplayName("Analyzes a single group end-to-end")
    void analyzesSingleGroup() throws Exception {
        StockInfo stock1 = new StockInfo(2, "00700", "腾讯");
        StockInfo stock2 = new StockInfo(2, "09988", "阿里");

        when(stockGroupService.getStocksInGroup("港股")).thenReturn(List.of(stock1, stock2));
        when(kLineService.getOrFetchKLines(any(StockInfo.class))).thenReturn(sampleKLines());
        when(llmClient.analyzeRightTrend(anyString(), anyString(), anyString(), anyList(), any()))
                .thenReturn(new LlmAnalysis(true, true, "high", "up", "near_top", "高位放量滞涨",
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
                .thenReturn(new LlmAnalysis(true, true, "medium", "up", "mid", "趋势中段无明显顶底信号",
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

    @Test
    @DisplayName("trendHistory：最近7个交易日、一天多条取最新、按日期升序")
    void trendHistoryLatestPerDayAscendingTruncatedTo7() throws Exception {
        StockInfo stock = new StockInfo(11, "AAPL", "Apple");
        when(stockGroupService.getStocksInGroup("美股")).thenReturn(List.of(stock));
        when(kLineService.getOrFetchKLines(any(StockInfo.class))).thenReturn(sampleKLines());
        when(llmClient.analyzeRightTrend(anyString(), anyString(), anyString(), anyList(), any()))
                .thenReturn(new LlmAnalysis(true, true, "high", "up", "mid", "趋势中段",
                        List.of("higher lows"), "trending up", null));
        // mock 返回顺序即 createdAt desc（首条=当天最新一条）
        when(repository.findByStockKeyOrderByCreatedAtDesc("11.AAPL")).thenReturn(List.of(
                rec("2026-08-10", true),
                rec("2026-08-10", false),  // 同一天较早一条，应被忽略
                rec("2026-08-09", false),
                rec("2026-08-08", true),
                rec("2026-08-07", true),
                rec("2026-08-06", false),
                rec("2026-08-05", true),
                rec("2026-08-04", true),
                rec("2026-08-03", true),   // 第 8 天起截断
                rec("2026-08-02", true)
        ));

        RightTrendReport report = service.analyzeGroup("美股");

        List<StockTrendResult.TrendDay> h = report.stocks().get(0).trendHistory();
        assertNotNull(h);
        assertEquals(7, h.size(), "只保留最近 7 个交易日");
        assertEquals("2026-08-04", h.get(0).date(), "升序：最老在前");
        assertEquals("2026-08-10", h.get(6).date(), "升序：最新在后");
        assertTrue(h.get(6).isInRightTrend(), "08-10 一天两条取最新一条 = true");
        assertFalse(h.get(2).isInRightTrend(), "08-06 = false");
        assertFalse(h.get(5).isInRightTrend(), "08-09 = false");
    }

    @Test
    @DisplayName("最新K线bar过旧时跳过分析并标记（不调LLM、不持久化）")
    void skipsStaleKlineData() throws Exception {
        StockInfo stock = new StockInfo(11, "COHR", "Coherent");
        when(stockGroupService.getStocksInGroup("美股")).thenReturn(List.of(stock));
        // 最后一根 bar = 2020-01-01，距今远超 maxStaleDays(=3)
        List<KLineService.KLineData> stale = List.of(
                new KLineService.KLineData("2019-12-30 00:00:00", 1, 1, 1, 1, 100L, 1.0),
                new KLineService.KLineData("2020-01-01 00:00:00", 1, 1, 1, 1, 100L, 13.44));
        when(kLineService.getOrFetchKLines(any(StockInfo.class))).thenReturn(stale);

        RightTrendReport report = service.analyzeGroup("美股");

        assertEquals(1, report.stocks().size());
        StockTrendResult r = report.stocks().get(0);
        assertFalse(r.success(), "标记为非成功");
        assertTrue(r.reason().contains("K线数据过旧"), "reason 标注数据过旧");
        assertTrue(r.reason().contains("2020-01-01"), "reason 含最新 bar 日期");
        verifyNoInteractions(llmClient);          // 不喂 LLM
        verify(repository, never()).save(any());  // 不持久化
    }

    private RightTrendAnalysisRecord rec(String tradeDate, boolean inTrend) {
        return new RightTrendAnalysisRecord("美股", "11.AAPL", "Apple",
                tradeDate, inTrend, "high", "up", "sig", "reason", "deepseek");
    }
}
