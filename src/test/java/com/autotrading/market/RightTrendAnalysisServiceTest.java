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
import org.mockito.ArgumentCaptor;

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
    @DisplayName("最新K线bar过旧时跳过分析并标记（不调LLM，但落库STALE供补偿器重试）")
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
        verifyNoInteractions(llmClient);  // 不喂 LLM
        // 现在 stale 路径落库（status=STALE），供补偿调度器后续重试
        ArgumentCaptor<RightTrendAnalysisRecord> captor = ArgumentCaptor.forClass(RightTrendAnalysisRecord.class);
        verify(repository).save(captor.capture());
        assertEquals("STALE", captor.getValue().getStatus(), "落库 status=STALE");
    }

    @Test
    @DisplayName("交易日感知：缺最近一个交易日bar（如8-14只有8-12）→ STALE，即使仅差2天")
    void missingLatestTradeDayBarIsStale() throws Exception {
        StockInfo stock = new StockInfo(11, "LITE", "Lumentum");
        when(stockGroupService.getStocksInGroup("美股")).thenReturn(List.of(stock));
        // bar 停在 4 天前 —— 无论今天周几，必早于预期交易日（expected 最早=昨天-2）；
        // 且 staleDays=4>3 也会走兜底，但 expected 分支优先，reason 应为"早于预期交易日"
        String oldBar = java.time.LocalDate.now().minusDays(4).toString();
        List<KLineService.KLineData> klines = List.of(
                new KLineService.KLineData(oldBar + " 00:00:00", 1, 1, 1, 1, 100L, 13.63));
        when(kLineService.getOrFetchKLines(any(StockInfo.class))).thenReturn(klines);

        RightTrendReport report = service.analyzeGroup("美股");

        StockTrendResult r = report.stocks().get(0);
        assertFalse(r.success(), "缺最近交易日 bar 应判 stale");
        assertTrue(r.reason().contains("K线数据过旧"), "reason 标注数据过旧");
        verifyNoInteractions(llmClient);  // 不喂 LLM

        ArgumentCaptor<RightTrendAnalysisRecord> captor = ArgumentCaptor.forClass(RightTrendAnalysisRecord.class);
        verify(repository).save(captor.capture());
        assertEquals("STALE", captor.getValue().getStatus(), "落库 status=STALE");
        assertTrue(captor.getValue().getErrorMessage().contains("早于预期交易日"),
                "errorMessage 指出早于预期交易日");
    }

    @Test
    @DisplayName("黑名单（exclude-codes）标的直接跳过：不拉K线、不调LLM、不落库")
    void excludesBlacklistedCodes() throws Exception {
        rightTrendProps.setExcludeCodes("BZmain, .VIX,CLmain,.SOX");
        StockInfo futures = new StockInfo(11, "BZmain", "Brent");
        StockInfo index = new StockInfo(11, ".VIX", "VIX");
        StockInfo apple = new StockInfo(11, "AAPL", "Apple");
        when(stockGroupService.getStocksInGroup("美股"))
                .thenReturn(List.of(futures, index, apple));
        when(kLineService.getOrFetchKLines(any(StockInfo.class))).thenReturn(sampleKLines());
        when(llmClient.analyzeRightTrend(anyString(), anyString(), anyString(), anyList(), any()))
                .thenReturn(new LlmAnalysis(true, true, "high", "up", "mid", "趋势中段",
                        List.of("突破MA30"), "uptrend", null));

        RightTrendReport report = service.analyzeGroup("美股");

        assertEquals(1, report.stocks().size(), "黑名单 2 只被跳过，只剩 AAPL");
        assertEquals("11.AAPL", report.stocks().get(0).stockKey());
        verify(repository, times(1)).save(any());
        verify(llmClient, times(1)).analyzeRightTrend(anyString(), anyString(), anyString(), anyList(), any());
    }

    @Test
    @DisplayName("补偿重试跳过黑名单标的且不落库（原记录已被补偿器删除，彻底清除）")
    void retrySkipsBlacklistedCode() {
        rightTrendProps.setExcludeCodes("BZmain");
        RightTrendAnalysisRecord record = new RightTrendAnalysisRecord(
                "美股", "11.BZmain", "Brent", "2026-08-14", false, "unknown",
                "unknown", "", "K线拉取失败", "deepseek", "FAILED", 1, null, "无权限");

        StockTrendResult result = service.retryStock(record, "deepseek");

        assertFalse(result.success());
        verify(repository, never()).save(any());
        verifyNoInteractions(kLineService, llmClient);
    }

    @Test
    @DisplayName("报告日期：美股组标美东最近已完成交易日，而非北京当天")
    void usGroupReportUsesEtTradeDate() throws Exception {
        StockInfo apple = new StockInfo(11, "AAPL", "Apple");
        when(stockGroupService.getStocksInGroup("美股")).thenReturn(List.of(apple));
        when(kLineService.getOrFetchKLines(any(StockInfo.class))).thenReturn(sampleKLines());
        when(llmClient.analyzeRightTrend(anyString(), anyString(), anyString(), anyList(), any()))
                .thenReturn(new LlmAnalysis(true, true, "high", "up", "mid", "趋势中段",
                        List.of("突破MA30"), "uptrend", null));

        RightTrendReport report = service.analyzeGroup("美股");

        String expectedEtDate = RightTrendAnalysisService.expectedLatestTradeDateAt(
                StockInfo.MARKET_US, java.time.ZonedDateTime.now());
        assertEquals(expectedEtDate, report.date(), "美股报告日期 = 美东最近已完成交易日");
    }

    @Test
    @DisplayName("报告日期：港股组仍用服务器本地日期；分组全失败时退回本地日期")
    void hkGroupReportUsesLocalDate() throws Exception {
        StockInfo tencent = new StockInfo(1, "00700", "腾讯");
        when(stockGroupService.getStocksInGroup("港股")).thenReturn(List.of(tencent));
        when(kLineService.getOrFetchKLines(any(StockInfo.class))).thenReturn(sampleKLines());
        when(llmClient.analyzeRightTrend(anyString(), anyString(), anyString(), anyList(), any()))
                .thenReturn(new LlmAnalysis(true, true, "high", "up", "mid", "趋势中段",
                        List.of("突破MA30"), "uptrend", null));

        RightTrendReport report = service.analyzeGroup("港股");
        assertEquals(java.time.LocalDate.now().toString(), report.date(), "港股报告日期 = 本地当天");

        when(stockGroupService.getStocksInGroup("空组"))
                .thenThrow(new com.autotrading.futu.AsyncRequestBridge.FutuRequestException("Not connected"));
        RightTrendReport empty = service.analyzeGroup("空组");
        assertEquals(java.time.LocalDate.now().toString(), empty.date(), "无股票时退回本地当天");
    }

    private RightTrendAnalysisRecord rec(String tradeDate, boolean inTrend) {
        return new RightTrendAnalysisRecord("美股", "11.AAPL", "Apple",
                tradeDate, inTrend, "high", "up", "sig", "reason", "deepseek");
    }
}
