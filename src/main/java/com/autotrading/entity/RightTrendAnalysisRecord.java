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

    @Column(nullable = false)
    private Instant createdAt;

    public RightTrendAnalysisRecord() {}

    public RightTrendAnalysisRecord(String groupName, String stockKey, String stockName,
                                     String tradeDate, boolean isInRightTrend, String confidence,
                                     String trendDirection, String keySignals, String reason,
                                     String provider) {
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
    public Instant getCreatedAt() { return createdAt; }
}
