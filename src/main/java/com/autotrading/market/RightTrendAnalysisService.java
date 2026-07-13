package com.autotrading.market;

import com.autotrading.account.StockGroupService;
import com.autotrading.config.DeepSeekProperties;
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
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates right-trend analysis: fetch stocks from Futu groups,
 * pull K-line data, call DeepSeek per stock, collect results.
 */
@Service
public class RightTrendAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(RightTrendAnalysisService.class);

    private final StockGroupService stockGroupService;
    private final KLineService kLineService;
    private final DeepSeekClient deepSeekClient;
    private final DeepSeekProperties deepSeekProperties;
    private final RightTrendProperties rightTrendProperties;
    private final RightTrendAnalysisRecordRepository repository;

    public RightTrendAnalysisService(StockGroupService stockGroupService,
                                       KLineService kLineService,
                                       DeepSeekClient deepSeekClient,
                                       DeepSeekProperties deepSeekProperties,
                                       RightTrendProperties rightTrendProperties,
                                       RightTrendAnalysisRecordRepository repository) {
        this.stockGroupService = stockGroupService;
        this.kLineService = kLineService;
        this.deepSeekClient = deepSeekClient;
        this.deepSeekProperties = deepSeekProperties;
        this.rightTrendProperties = rightTrendProperties;
        this.repository = repository;
    }

    /**
     * Analyzes a single group.
     */
    public RightTrendReport analyzeGroup(String groupName) {
        return analyzeGroups(List.of(groupName));
    }

    /**
     * Analyzes multiple groups and merges results into a single report.
     */
    public RightTrendReport analyzeGroups(List<String> groupNames) {
        String tradeDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        log.info("Starting right-trend analysis for groups: {} on {}", groupNames, tradeDate);

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
                StockTrendResult result = analyzeSingleStock(stock, groupName, tradeDate);
                results.add(result);

                // Rate limit between API calls
                if (deepSeekProperties.getApi().getRateLimitMs() > 0) {
                    try {
                        Thread.sleep(deepSeekProperties.getApi().getRateLimitMs());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        RightTrendReport report = new RightTrendReport(tradeDate, groupNames, results, System.currentTimeMillis());
        log.info("Right-trend analysis complete: {} stocks analyzed, {} in right trend",
                results.size(), results.stream().filter(StockTrendResult::isInRightTrend).count());
        return report;
    }

    private StockTrendResult analyzeSingleStock(StockInfo stock, String groupName, String tradeDate) {
        String marketLabel = marketLabel(stock.getMarket());

        // Cache-first: uses in-memory cache if available, only hits Futu API on miss
        List<KLineService.KLineData> klines = kLineService.getOrFetchKLines(stock);
        if (klines.isEmpty()) {
            log.warn("No K-line data for {} (cache miss + API failed/quota)", stock.key());
            return StockTrendResult.failed(stock, groupName);
        }

        // Take last N bars (default 60)
        int lookback = rightTrendProperties.getKlineLookback();
        int fromIndex = Math.max(0, klines.size() - lookback);
        List<KLineService.KLineData> recentKlines = klines.subList(fromIndex, klines.size());

        DeepSeekClient.DeepSeekAnalysis analysis = deepSeekClient.analyzeRightTrend(
                stock.getName(), stock.key(), marketLabel, recentKlines);

        StockTrendResult result;
        if (analysis.success()) {
            result = new StockTrendResult(
                    stock.key(), stock.getName(), groupName,
                    analysis.isInRightTrend(), analysis.confidence(),
                    analysis.trendDirection(), analysis.keySignals(),
                    analysis.reason(), true
            );
            persistRecord(result, tradeDate);
        } else {
            result = StockTrendResult.failed(stock, groupName);
        }

        return result;
    }

    private void persistRecord(StockTrendResult result, String tradeDate) {
        try {
            RightTrendAnalysisRecord record = new RightTrendAnalysisRecord(
                    result.groupName(), result.stockKey(), result.stockName(),
                    tradeDate, result.isInRightTrend(), result.confidence(),
                    result.trendDirection(), String.join("; ", result.keySignals()),
                    result.reason()
            );
            repository.save(record);
        } catch (Exception e) {
            log.warn("Failed to persist analysis record for {}: {}", result.stockKey(), e.getMessage());
        }
    }

    private String marketLabel(int market) {
        if (market == 11) return "美股";
        if (market == 2) return "港股";
        if (market == 21) return "A股(沪)";
        if (market == 22) return "A股(深)";
        return "M" + market;
    }

    // ---- DTOs ----

    public record RightTrendReport(String date, List<String> groupNames,
                                     List<StockTrendResult> stocks,
                                     long generatedAt) {}

    public record StockTrendResult(String stockKey, String stockName, String groupName,
                                     boolean isInRightTrend, String confidence,
                                     String trendDirection, List<String> keySignals,
                                     String reason, boolean success) {
        static StockTrendResult failed(StockInfo stock, String groupName) {
            return new StockTrendResult(stock.key(), stock.getName(), groupName,
                    false, "unknown", "unknown", List.of(),
                    "Analysis failed", false);
        }
    }
}
