package com.autotrading.entity;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Persisted AI analysis result from external API.
 */
@Entity
@Table(name = "stock_analysis_records")
public class StockAnalysisRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String stockKey;

    @Column(nullable = false)
    private String market;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String tradeDate;

    private String rating;           // BUY, SELL, HOLD

    private double targetPrice;

    @Column(length = 2000)
    private String summary;

    @Column(length = 32768)
    private String rawResult;       // Store full API response (external responses can be ~20KB+)

    @Column(nullable = false)
    private Instant createdAt;

    public StockAnalysisRecord() {}

    public StockAnalysisRecord(String stockKey, String market, String code,
                                String tradeDate, String rating, double targetPrice,
                                String summary, String rawResult) {
        this.stockKey = stockKey;
        this.market = market;
        this.code = code;
        this.tradeDate = tradeDate;
        this.rating = rating;
        this.targetPrice = targetPrice;
        this.summary = summary;
        this.rawResult = rawResult;
        this.createdAt = Instant.now();
    }

    // Getters
    public Long getId() { return id; }
    public String getStockKey() { return stockKey; }
    public String getMarket() { return market; }
    public String getCode() { return code; }
    public String getTradeDate() { return tradeDate; }
    public String getRating() { return rating; }
    public double getTargetPrice() { return targetPrice; }
    public String getSummary() { return summary; }
    public String getRawResult() { return rawResult; }
    public Instant getCreatedAt() { return createdAt; }
}
