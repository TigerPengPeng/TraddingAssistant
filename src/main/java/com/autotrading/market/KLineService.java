package com.autotrading.market;

import com.autotrading.futu.AsyncRequestBridge;
import com.autotrading.futu.FutuConnectionManager;
import com.autotrading.model.StockInfo;
import com.futu.openapi.FTAPI_Conn_Qot;
import com.futu.openapi.pb.Common;
import com.futu.openapi.pb.QotCommon.*;
import com.futu.openapi.pb.QotRequestHistoryKL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class KLineService {

    private static final Logger log = LoggerFactory.getLogger(KLineService.class);
    private static final int MAX_KL_COUNT = 120;
    /** OpenD 历史K线限流（免费档 60 次/30 秒）时的最大重试次数（退避后重试）。 */
    private static final int MAX_KL_RATELIMIT_RETRIES = 2;
    /** 限流退避毫秒（OpenD 限流窗口 30 秒，等 5 秒后重试通常可过）。 */
    private static final long RATELIMIT_BACKOFF_MS = 5_000L;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** Frequency labels used across the app. */
    public static final String FREQ_DAY = "day";
    public static final String FREQ_WEEK = "week";

    /** Cache-key prefix for weekly bars, to namespace daily (bare stockKey) from weekly. */
    private static final String WEEK_PREFIX = "W:";

    private final FutuConnectionManager connectionManager;
    private final AsyncRequestBridge bridge;
    private final long klineCacheTtlMinutes;

    private final Map<String, List<Double>> closePriceCache = new ConcurrentHashMap<>();
    private final Map<String, CachedKLines> klineCache = new ConcurrentHashMap<>();

    public KLineService(FutuConnectionManager connectionManager, AsyncRequestBridge bridge,
                        @Value("${kline.cache-ttl-minutes:360}") long klineCacheTtlMinutes) {
        this.connectionManager = connectionManager;
        this.bridge = bridge;
        this.klineCacheTtlMinutes = klineCacheTtlMinutes;
    }

    public List<Double> fetchDailyCloses(StockInfo stock) throws AsyncRequestBridge.FutuRequestException {
        List<KLineData> klines = fetchKLines(stock);
        List<Double> closes = new ArrayList<>();
        for (KLineData k : klines) closes.add(k.close());
        return closes;
    }

    public List<KLineData> fetchKLines(StockInfo stock) throws AsyncRequestBridge.FutuRequestException {
        return fetchKLines(stock, KLType.KLType_Day_VALUE);
    }

    /**
     * Fetches K-lines for a stock at the given Futu KL type value (e.g.
     * {@link KLType#KLType_Day_VALUE}, {@link KLType#KLType_Week_VALUE}).
     * The date window is widened for lower-frequency bars so maxAckKLNum
     * returns the most recent bars.
     */
    public List<KLineData> fetchKLines(StockInfo stock, int klTypeValue) throws AsyncRequestBridge.FutuRequestException {
        FTAPI_Conn_Qot conn = connectionManager.getConnQot();
        if (conn == null) throw new AsyncRequestBridge.FutuRequestException("Not connected to OpenD");

        String endDate = LocalDate.now().format(DATE_FMT);
        // Day: 120 trading days ~= 175 calendar days. Week: 120 weeks ~= 900 days.
        long lookbackDays = klTypeValue == KLType.KLType_Week_VALUE ? 900L : 175L;
        String beginDate = LocalDate.now().minusDays(lookbackDays).format(DATE_FMT);

        QotRequestHistoryKL.Request request = QotRequestHistoryKL.Request.newBuilder()
                .setC2S(QotRequestHistoryKL.C2S.newBuilder()
                        .setSecurity(Security.newBuilder()
                                .setMarket(stock.getMarket()).setCode(stock.getCode()).build())
                        .setKlType(klTypeValue)
                        .setRehabType(RehabType.RehabType_Forward_VALUE)
                        .setBeginTime(beginDate).setEndTime(endDate)
                        .setMaxAckKLNum(MAX_KL_COUNT).build())
                .build();

        QotRequestHistoryKL.Response response = null;
        for (int attempt = 0; ; attempt++) {
            int serial = conn.requestHistoryKL(request);
            response = bridge.await(serial, QotRequestHistoryKL.Response.class);
            if (response.getRetType() == Common.RetType.RetType_Succeed_VALUE) break;
            String retMsg = response.getRetMsg();
            // OpenD 限流（60 次/30 秒）属瞬时错误，退避后重试而非直接失败
            if (isRateLimited(retMsg) && attempt < MAX_KL_RATELIMIT_RETRIES) {
                log.warn("RequestHistoryKL rate-limited for {} ({}), retry {}/{} after {}ms",
                        stock.key(), retMsg, attempt + 1, MAX_KL_RATELIMIT_RETRIES, RATELIMIT_BACKOFF_MS);
                try {
                    Thread.sleep(RATELIMIT_BACKOFF_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            throw new AsyncRequestBridge.FutuRequestException("RequestHistoryKL failed: " + retMsg);
        }

        List<KLineData> klines = new ArrayList<>();
        for (KLine kl : response.getS2C().getKlListList()) {
            if (kl.getIsBlank()) continue;
            klines.add(new KLineData(kl.getTime(), kl.getOpenPrice(), kl.getHighPrice(),
                    kl.getLowPrice(), kl.getClosePrice(), kl.getVolume(), kl.getChangeRate()));
        }
        String cacheKey = cacheKey(stock.key(), klTypeValue);
        klineCache.put(cacheKey, new CachedKLines(klines, System.currentTimeMillis()));
        closePriceCache.put(cacheKey, klines.stream().map(KLineData::close).toList());
        log.debug("Fetched {} {} klines for {}", klines.size(),
                klTypeValue == KLType.KLType_Week_VALUE ? "weekly" : "daily", stock.key());
        return klines;
    }

    /** Fetches K-lines for the given frequency label ("day" or "week"). */
    public List<KLineData> fetchKLines(StockInfo stock, String frequency) throws AsyncRequestBridge.FutuRequestException {
        return fetchKLines(stock, klTypeValue(frequency));
    }

    public List<KLineData> getKLines(int market, String code) {
        return getKLines(market, code, FREQ_DAY);
    }

    /**
     * Returns K-lines for the given frequency. Always fetches fresh data so the
     * detail page shows the latest bars.
     */
    public List<KLineData> getKLines(int market, String code, String frequency) {
        String key = market + "." + code;
        try { return fetchKLines(new StockInfo(market, code, code), frequency); }
        catch (Exception e) { log.warn("Failed to fetch klines for {}: {}", key, e.getMessage()); return List.of(); }
    }

    public void fetchAll(List<StockInfo> stocks) {
        fetchAll(stocks, FREQ_DAY);
    }

    /** Fetches and caches closes for all stocks at the given frequency. */
    public void fetchAll(List<StockInfo> stocks, String frequency) {
        int success = 0;
        for (StockInfo s : stocks) {
            try { fetchKLines(s, frequency); success++; }
            catch (Exception e) { log.warn("Failed to fetch {} K-lines for {}: {}",
                    frequency, s.key(), e.getMessage()); }
        }
        log.info("{} K-line fetch complete: {}/{} stocks", frequency, success, stocks.size());
    }

    public List<Double> getCloses(String stockKey) {
        return getCloses(stockKey, FREQ_DAY);
    }

    /** Returns cached closes for a stock at the given frequency (empty if none). */
    public List<Double> getCloses(String stockKey, String frequency) {
        return closePriceCache.getOrDefault(cacheKey(stockKey, klTypeValue(frequency)), List.of());
    }

    public void refreshAll(List<StockInfo> stocks) {
        fetchAll(stocks);
    }

    public int cachedStockCount() {
        return (int) closePriceCache.keySet().stream()
                .filter(k -> !k.startsWith(WEEK_PREFIX))
                .count();
    }

    /** Maps a frequency label to the Futu KL type value. */
    private int klTypeValue(String frequency) {
        return FREQ_WEEK.equalsIgnoreCase(frequency) ? KLType.KLType_Week_VALUE : KLType.KLType_Day_VALUE;
    }

    /** OpenD 历史K线限流响应识别（"high frequency ... Maximum 60 times per 30 seconds"）。 */
    static boolean isRateLimited(String retMsg) {
        if (retMsg == null) return false;
        return retMsg.contains("high frequency")
                || (retMsg.contains("Maximum") && retMsg.contains("per"));
    }

    /** Cache key namespaces daily (bare) from weekly (prefixed). */
    private String cacheKey(String stockKey, int klTypeValue) {
        return klTypeValue == KLType.KLType_Week_VALUE ? WEEK_PREFIX + stockKey : stockKey;
    }

    /**
     * Returns daily K-lines from cache if available, otherwise fetches from Futu API.
     * Uses cache to conserve the Futu historical K-line quota (100/7 days for free tier).
     * Returns empty list if cache miss and API call fails.
     */
    public List<KLineData> getOrFetchKLines(StockInfo stock) {
        String key = stock.key();
        CachedKLines cached = klineCache.get(key);
        if (cached != null && !isExpired(cached)) {
            log.debug("K-line cache hit for {}", key);
            return cached.klines();
        }
        if (cached != null) {
            log.debug("K-line cache expired for {} (ttl={}min), refetching", key, klineCacheTtlMinutes);
        }
        try {
            return fetchKLines(stock);
        } catch (AsyncRequestBridge.FutuRequestException e) {
            log.warn("Failed to fetch K-lines for {}: {}", key, e.getMessage());
            return List.of();
        }
    }

    /** Drops cached K-lines for a stock so the next getOrFetchKLines refetches. */
    public void invalidate(StockInfo stock) {
        klineCache.remove(stock.key());
    }

    private boolean isExpired(CachedKLines entry) {
        return klineCacheTtlMinutes > 0
                && System.currentTimeMillis() - entry.fetchedAtMillis() > klineCacheTtlMinutes * 60_000L;
    }

    public record KLineData(String time, double open, double high, double low, double close,
                            long volume, double changeRate) {}

    /** Cached K-lines plus the epoch-millis timestamp they were fetched at. */
    private record CachedKLines(List<KLineData> klines, long fetchedAtMillis) {}
}
