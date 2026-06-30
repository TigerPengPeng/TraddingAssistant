package com.autotrading.repository;

import com.autotrading.entity.StockAnalysisRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StockAnalysisRecordRepository extends JpaRepository<StockAnalysisRecord, Long> {
    List<StockAnalysisRecord> findByStockKeyOrderByCreatedAtDesc(String stockKey);
    List<StockAnalysisRecord> findByMarketAndCodeOrderByCreatedAtDesc(String market, String code);
}
