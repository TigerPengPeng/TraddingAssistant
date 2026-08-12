package com.autotrading.entity;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Persisted right-trend analysis result for a single stock.
 */
@Entity
@Table(name = "right_trend_analysis_records")
public class RightTrendAnalysisRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String groupName;

    @Column(nullable = false)
    private String stockKey;

    @Column(nullable = false)
    private String stockName;

    @Column(nullable = false)
    private String tradeDate;

    private boolean isInRightTrend;

    private String confidence;

    private String trendDirection;

    @Column(length = 2000)
    private String keySignals;

    @Column(length = 4000)
    private String reason;

    /** LLM provider id used for this analysis (deepseek/glm/kimi/...). */
    @Column(length = 32)
    private String provider;

    @Column(nullable = false, length = 32)
    private String status = "DONE";

    private int retryCount = 0;

    private Instant lastAttemptAt;

    @Column(length = 500)
    private String errorMessage;

    @Column(nullable = false)
    private Instant createdAt;

    public RightTrendAnalysisRecord() {}

    /** Legacy 全参构造（无补偿字段）：成功路径兼容，status=DONE。 */
    public RightTrendAnalysisRecord(String groupName, String stockKey, String stockName,
                                     String tradeDate, boolean isInRightTrend, String confidence,
                                     String trendDirection, String keySignals, String reason,
                                     String provider) {
        this(groupName, stockKey, stockName, tradeDate, isInRightTrend, confidence,
                trendDirection, keySignals, reason, provider, "DONE", 0, null, null);
    }

    /** 含补偿字段的完整构造（status/retryCount/lastAttemptAt/errorMessage）。 */
    public RightTrendAnalysisRecord(String groupName, String stockKey, String stockName,
                                     String tradeDate, boolean isInRightTrend, String confidence,
                                     String trendDirection, String keySignals, String reason,
                                     String provider, String status, int retryCount,
                                     Instant lastAttemptAt, String errorMessage) {
        this.groupName = groupName;
        this.stockKey = stockKey;
        this.stockName = stockName;
        this.tradeDate = tradeDate;
        this.isInRightTrend = isInRightTrend;
        this.confidence = confidence;
        this.trendDirection = trendDirection;
        this.keySignals = keySignals;
        this.reason = reason;
        this.provider = provider;
        this.status = status;
        this.retryCount = retryCount;
        this.lastAttemptAt = lastAttemptAt != null ? lastAttemptAt : Instant.now();
        this.errorMessage = errorMessage;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getGroupName() { return groupName; }
    public String getStockKey() { return stockKey; }
    public String getStockName() { return stockName; }
    public String getTradeDate() { return tradeDate; }
    public boolean getIsInRightTrend() { return isInRightTrend; }
    public String getConfidence() { return confidence; }
    public String getTrendDirection() { return trendDirection; }
    public String getKeySignals() { return keySignals; }
    public String getReason() { return reason; }
    public String getProvider() { return provider; }
    public String getStatus() { return status; }
    public int getRetryCount() { return retryCount; }
    public Instant getLastAttemptAt() { return lastAttemptAt; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }

    // ---- setters 仅给补偿器复用同一实体更新状态 ----
    public void setStatus(String status) { this.status = status; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public void setLastAttemptAt(Instant lastAttemptAt) { this.lastAttemptAt = lastAttemptAt; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public void setRightTrendResultFields(boolean isInRightTrend, String confidence,
                                            String trendDirection, String keySignals,
                                            String reason) {
        this.isInRightTrend = isInRightTrend;
        this.confidence = confidence;
        this.trendDirection = trendDirection;
        this.keySignals = keySignals;
        this.reason = reason;
    }
}
