package com.autotrading.market;

import com.autotrading.account.StockGroupService;
import com.autotrading.config.AiProviderProperties;
import com.autotrading.config.RightTrendProperties;
import com.autotrading.entity.RightTrendAnalysisRecord;
import com.autotrading.futu.AsyncRequestBridge;
import com.autotrading.model.StockInfo;
import com.autotrading.repository.RightTrendAnalysisRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Orchestrates right-trend analysis: fetch stocks from Futu groups,
 * pull K-line data, call DeepSeek per stock, collect results.
 */
@Service
public class RightTrendAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(RightTrendAnalysisService.class);

    /** 右侧趋势列展示的最近交易日数（DB 有记录的最近 N 个不同 tradeDate） */
    private static final int TREND_HISTORY_DAYS = 7;

    private final StockGroupService stockGroupService;
    private final KLineService kLineService;
    private final LlmAnalysisClient llmClient;
    private final AiProviderProperties aiProviderProperties;
    private final RightTrendProperties rightTrendProperties;
    private final RightTrendAnalysisRecordRepository repository;

    public RightTrendAnalysisService(StockGroupService stockGroupService,
                                       KLineService kLineService,
                                       LlmAnalysisClient llmClient,
                                       AiProviderProperties aiProviderProperties,
                                       RightTrendProperties rightTrendProperties,
                                       RightTrendAnalysisRecordRepository repository) {
        this.stockGroupService = stockGroupService;
        this.kLineService = kLineService;
        this.llmClient = llmClient;
        this.aiProviderProperties = aiProviderProperties;
        this.rightTrendProperties = rightTrendProperties;
        this.repository = repository;
    }

    /**
     * Analyzes a single group.
     */
    public RightTrendReport analyzeGroup(String groupName) {
        return analyzeGroups(List.of(groupName), null);
    }

    /**
    * Analyzes multiple groups and merges results into a single report.
     * Uses the default provider (ai.default-provider).
     */
    public RightTrendReport analyzeGroups(List<String> groupNames) {
        return analyzeGroups(groupNames, null);
    }

    /**
     * Analyzes multiple groups using the given provider id (null/unknown ->
     * default provider). Merges results into a single report.
     */
    public RightTrendReport analyzeGroups(List<String> groupNames, String providerId) {
        String tradeDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        AiProviderProperties.Resolved resolved = aiProviderProperties.resolve(providerId);
        String providerIdResolved = resolved == null ? null : resolved.id();
        String providerLabel = resolved == null ? null : resolved.provider().getLabel();
        log.info("Starting right-trend analysis for groups: {} on {} (provider={})",
                groupNames, tradeDate, providerIdResolved);

        List<StockTrendResult> results = new ArrayList<>();

        for (String groupName : groupNames) {
            List<StockInfo> stocks;
            try {
                stocks = stockGroupService.getStocksInGroup(groupName);
            } catch (AsyncRequestBridge.FutuRequestException e) {
                log.error("Failed to fetch stocks from group [{}]: {}", groupName, e.getMessage());
                continue;
            }
            log.info("Group [{}] contains {} stocks", groupName, stocks.size());

            for (StockInfo stock : stocks) {
                StockTrendResult result = analyzeSingleStock(stock, groupName, tradeDate, resolved);
                results.add(result);

                // Rate limit between API calls (per-provider)
                long rateLimitMs = resolved == null ? 0 : resolved.provider().getRateLimitMs();
                if (rateLimitMs > 0) {
                    try {
                        Thread.sleep(rateLimitMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        RightTrendReport report = new RightTrendReport(tradeDate, groupNames, results,
                System.currentTimeMillis(), providerIdResolved, providerLabel);
        log.info("Right-trend analysis complete: {} stocks analyzed, {} in right trend",
                results.size(), results.stream().filter(StockTrendResult::isInRightTrend).count());
        return report;
    }

    /**
     * 补偿调度器重试入口：从 DB 待补偿记录重建 StockInfo，复用 analyzeSingleStock 全流程
     * （K线校验 + stale + LLM + persistRecord）。
     *
     * <p>落库策略：补偿器在调用前**删除原失败记录**，本方法重新 insert 一条新记录
     * （status 反映本次重试结果）。成功时新记录 DONE，同 tradeDate 旧 DONE 被 SUPERSEDED。
     * STALE 超期由补偿器直接 update 原记录为 FAILED（不调用本方法）。
     */
    public StockTrendResult retryStock(RightTrendAnalysisRecord record, String providerId) {
        AiProviderProperties.Resolved resolved = aiProviderProperties.resolve(providerId);
        StockInfo stock = stockInfoFromKey(record.getStockKey(), record.getStockName());
        if (stock == null) {
            log.warn("Cannot retry {}: bad stockKey {}", record.getStockKey(), record.getStockKey());
            return StockTrendResult.failed(
                    new StockInfo(11, record.getStockKey(), record.getStockName()), record.getGroupName());
        }
        return analyzeSingleStock(stock, record.getGroupName(), record.getTradeDate(), resolved);
    }

    /** "market.code" → StockInfo；解析失败返回 null。 */
    private StockInfo stockInfoFromKey(String stockKey, String stockName) {
        if (stockKey == null) return null;
        int dot = stockKey.indexOf('.');
        if (dot <= 0 || dot >= stockKey.length() - 1) return null;
        try {
            int market = Integer.parseInt(stockKey.substring(0, dot));
            String code = stockKey.substring(dot + 1);
            return new StockInfo(market, code, stockName);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private StockTrendResult analyzeSingleStock(StockInfo stock, String groupName, String tradeDate,
                                                  AiProviderProperties.Resolved resolved) {
        String marketLabel = marketLabel(stock.getMarket());

        // Invalidate cache so the analysis reflects the latest closed bars, not
        // stale data left over from earlier runs within the process lifetime.
        kLineService.invalidate(stock);
        List<KLineService.KLineData> klines = kLineService.getOrFetchKLines(stock);
        if (klines.isEmpty()) {
            log.warn("No K-line data for {} (cache miss + API failed/quota)", stock.key());
            StockTrendResult failed = StockTrendResult.failed(stock, groupName);
            persistRecord(failed, tradeDate, resolved == null ? null : resolved.id(),
                    "FAILED", "K线拉取失败（限流/无权限/不支持）");
            return failed;
        }

        // 最新 bar 日期校验：OpenD 数据源可能延迟缺最新交易日 bar，
        // 用旧 bar 出报告会导致涨跌幅 / LLM 判断与实际相反（见 staleData）。
        KLineService.KLineData lastBar = klines.get(klines.size() - 1);
        long staleDays = staleDays(lastBar);
        if (staleDays > rightTrendProperties.getMaxStaleDays()) {
            log.warn("Stale K-line for {}: latest bar {} ({} days old > max {}), skipping analysis",
                    stock.key(), lastBar.time(), staleDays, rightTrendProperties.getMaxStaleDays());
            StockTrendResult stale = StockTrendResult.staleData(stock, groupName, lastBar.time());
            persistRecord(stale, tradeDate, resolved == null ? null : resolved.id(),
                    "STALE", "K线最新bar=" + lastBar.time().substring(0, 10) + " 距今" + staleDays + "天");
            return stale;
        }

        // Take last N bars (default 60)
        int lookback = rightTrendProperties.getKlineLookback();
        int fromIndex = Math.max(0, klines.size() - lookback);
        List<KLineService.KLineData> recentKlines = klines.subList(fromIndex, klines.size());

        // Volume anomaly vs prior N-day average (powers the email's volume section).
        StockTrendResult.VolumeAnomaly volume = computeVolumeAnomaly(recentKlines);

        // When no provider is configured at all, fail fast rather than NPE.
        if (resolved == null) {
            log.warn("No LLM provider configured; skipping analysis for {}", stock.key());
            StockTrendResult failed = StockTrendResult.failed(stock, groupName);
            persistRecord(failed, tradeDate, null, "FAILED", "未配置LLM供应商");
            return failed;
        }

        LlmAnalysisClient.LlmAnalysis analysis = llmClient.analyzeRightTrend(
                stock.getName(), stock.key(), marketLabel, recentKlines, resolved.provider());

        StockTrendResult result;
        if (analysis.success()) {
            result = new StockTrendResult(
                    stock.key(), stock.getName(), groupName,
                    analysis.isInRightTrend(), analysis.confidence(),
                    analysis.trendDirection(), analysis.keySignals(),
                    analysis.reason(), true, analysis.topBottomSignal(), analysis.topBottomReason(), volume
            );
            persistRecord(result, tradeDate, resolved.id(), "DONE", null);
            // 查最近7个交易日历史（persist 后含今天），填入结果用于前端/邮件色块展示
            List<StockTrendResult.TrendDay> history = buildTrendHistory(stock.key());
            result = new StockTrendResult(
                    result.stockKey(), result.stockName(), result.groupName(),
                    result.isInRightTrend(), result.confidence(),
                    result.trendDirection(), result.keySignals(),
                    result.reason(), true, result.topBottomSignal(), result.topBottomReason(),
                    result.volume(), history
            );
        } else {
            log.warn("LLM analysis failed for {}", stock.key());
            result = StockTrendResult.failed(stock, groupName);
            persistRecord(result, tradeDate, resolved.id(), "ANALYSIS_FAILED",
                    "LLM返回失败：" + (analysis.reason() == null ? "未知" : analysis.reason()));
        }

        return result;
    }

    /**
     * latest-vol / avg(prior `window` bars); anomaly when ratio >= configured threshold.
     * Returns null when there isn't enough history (window+1 bars) to compute.
     */
    private StockTrendResult.VolumeAnomaly computeVolumeAnomaly(List<KLineService.KLineData> klines) {
        int window = rightTrendProperties.getVolumeAnomalyWindow();
        if (klines.size() < window + 1) return null;
        int last = klines.size() - 1;
        long latestVol = klines.get(last).volume();
        double avgVol = klines.subList(last - window, last).stream()
                .mapToLong(KLineService.KLineData::volume).average().orElse(0);
        double ratio = avgVol > 0 ? (double) latestVol / avgVol : 0;
        double dayChangePct = klines.get(last).changeRate();
        boolean anomaly = ratio >= rightTrendProperties.getVolumeAnomalyRatio();
        return new StockTrendResult.VolumeAnomaly(latestVol, avgVol, ratio, dayChangePct, anomaly);
    }

    private void persistRecord(StockTrendResult result, String tradeDate, String providerId) {
        persistRecord(result, tradeDate, providerId,
                result.success() ? "DONE" : "FAILED", null);
    }

    /** 落库（含 status + errorMessage）；成功时同时把同 tradeDate 旧记录置 SUPERSEDED。 */
    private void persistRecord(StockTrendResult result, String tradeDate, String providerId,
                                 String status, String errorMessage) {
        try {
            RightTrendAnalysisRecord record = new RightTrendAnalysisRecord(
                    result.groupName(), result.stockKey(), result.stockName(),
                    tradeDate, result.isInRightTrend(), result.confidence(),
                    result.trendDirection(), String.join("; ", result.keySignals()),
                    result.reason(), providerId, status, 0, null, errorMessage
            );
            repository.save(record);
            if ("DONE".equals(status)) {
                markSuperseded(result.stockKey(), tradeDate, record.getId());
            }
        } catch (Exception e) {
            log.warn("Failed to persist analysis record for {}: {}", result.stockKey(), e.getMessage());
        }
    }

    /** 同 tradeDate 同股的旧 DONE 记录置 SUPERSEDED（成功被更新记录取代，一天取最新）。 */
    private void markSuperseded(String stockKey, String tradeDate, Long exceptId) {
        try {
            List<RightTrendAnalysisRecord> existing =
                    repository.findByStockKeyAndTradeDateOrderByCreatedAtDesc(stockKey, tradeDate);
            for (RightTrendAnalysisRecord r : existing) {
                if (exceptId != null && exceptId.equals(r.getId())) continue;
                if ("DONE".equals(r.getStatus())) {
                    r.setStatus("SUPERSEDED");
                    repository.save(r);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to mark superseded for {} {}: {}", stockKey, tradeDate, e.getMessage());
        }
    }

    /**
     * 最近 {@value #TREND_HISTORY_DAYS} 个交易日的趋势序列（左老→右新）。
     * 复用 {@code findByStockKeyOrderByCreatedAtDesc}（按 createdAt 降序），
     * 按 tradeDate 分组取每组首条（=当天最新一条），再按日期升序截取最近 N 个。
     * 不足 N 天时返回已有的；无任何历史返回空表。
     */
    private List<StockTrendResult.TrendDay> buildTrendHistory(String stockKey) {
        List<RightTrendAnalysisRecord> all = repository.findByStockKeyOrderByCreatedAtDesc(stockKey);
        LinkedHashMap<String, Boolean> latestByDate = new LinkedHashMap<>();
        for (RightTrendAnalysisRecord r : all) {
            latestByDate.putIfAbsent(r.getTradeDate(), r.getIsInRightTrend());
        }
        List<String> dates = new ArrayList<>(latestByDate.keySet());
        Collections.sort(dates); // yyyy-MM-dd 字典序即日期升序
        int from = Math.max(0, dates.size() - TREND_HISTORY_DAYS);
        List<StockTrendResult.TrendDay> history = new ArrayList<>();
        for (String d : dates.subList(from, dates.size())) {
            history.add(new StockTrendResult.TrendDay(d, latestByDate.get(d)));
        }
        return history;
    }

    private String marketLabel(int market) {
        if (market == StockInfo.MARKET_US) return "美股";
        if (market == StockInfo.MARKET_HK) return "港股";
        if (market == StockInfo.MARKET_CN_SH) return "A股(沪)";
        if (market == StockInfo.MARKET_CN_SZ) return "A股(深)";
        return "M" + market;
    }

    /** 最新 bar 距今天数（日历日）；time 形如 "2026-08-07" 或 "2026-08-07 00:00:00"。解析失败返回 0（不阻断）。 */
    private long staleDays(KLineService.KLineData lastBar) {
        try {
            String t = lastBar.time();
            String datePart = t.length() >= 10 ? t.substring(0, 10) : t;
            return ChronoUnit.DAYS.between(LocalDate.parse(datePart), LocalDate.now());
        } catch (Exception e) {
            log.warn("Failed to parse K-line time [{}] for staleness check: {}", lastBar.time(), e.getMessage());
            return 0;
        }
    }

    // ---- DTOs ----

    public record RightTrendReport(String date, List<String> groupNames,
                                     List<StockTrendResult> stocks,
                                     long generatedAt,
                                     String providerId, String providerLabel) {}

    public record StockTrendResult(String stockKey, String stockName, String groupName,
                                     boolean isInRightTrend, String confidence,
                                     String trendDirection, List<String> keySignals,
                                     String reason, boolean success,
                                     String topBottomSignal, String topBottomReason,
                                     VolumeAnomaly volume,
                                     List<TrendDay> trendHistory) {

        public record VolumeAnomaly(long latestVol, double avgVol, double ratio,
                                     double dayChangePct, boolean anomaly) {}

        /** 单日趋势点：date=yyyy-MM-dd（tradeDate），isInRightTrend=当日是否进入右侧趋势 */
        public record TrendDay(String date, boolean isInRightTrend) {}

        // Legacy 12-arg constructor (volume but no trendHistory)
        public StockTrendResult(String stockKey, String stockName, String groupName,
                                 boolean isInRightTrend, String confidence,
                                 String trendDirection, List<String> keySignals,
                                 String reason, boolean success,
                                 String topBottomSignal, String topBottomReason,
                                 VolumeAnomaly volume) {
            this(stockKey, stockName, groupName, isInRightTrend, confidence,
                 trendDirection, keySignals, reason, success,
                 topBottomSignal, topBottomReason, volume, null);
        }

        // Legacy 9-arg constructor for callers that don't compute volume (tests, etc.)
        public StockTrendResult(String stockKey, String stockName, String groupName,
                                 boolean isInRightTrend, String confidence,
                                 String trendDirection, List<String> keySignals,
                                 String reason, boolean success) {
            this(stockKey, stockName, groupName, isInRightTrend, confidence,
                 trendDirection, keySignals, reason, success, null, null, null, null);
        }

        static StockTrendResult failed(StockInfo stock, String groupName) {
            return new StockTrendResult(stock.key(), stock.getName(), groupName,
                    false, "unknown", "unknown", List.of(),
                    "Analysis failed", false, null, null, null, null);
        }

        /** K 线数据过旧（最新 bar 非最近预期交易日）→ 不喂 LLM，标记跳过。 */
        static StockTrendResult staleData(StockInfo stock, String groupName, String latestBarDate) {
            return new StockTrendResult(stock.key(), stock.getName(), groupName,
                    false, "unknown", "unknown", List.of(),
                    "K线数据过旧（最新 " + latestBarDate + "），已跳过分析", false, null, null, null, null);
        }
    }
}
