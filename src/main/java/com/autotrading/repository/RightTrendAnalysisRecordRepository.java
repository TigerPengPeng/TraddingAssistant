package com.autotrading.repository;

import com.autotrading.entity.RightTrendAnalysisRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RightTrendAnalysisRecordRepository extends JpaRepository<RightTrendAnalysisRecord, Long> {

    List<RightTrendAnalysisRecord> findByGroupNameAndTradeDateOrderByCreatedAtDesc(
            String groupName, String tradeDate);

    List<RightTrendAnalysisRecord> findByStockKeyOrderByCreatedAtDesc(String stockKey);

    List<RightTrendAnalysisRecord> findByTradeDateOrderByCreatedAtDesc(String tradeDate);
}
