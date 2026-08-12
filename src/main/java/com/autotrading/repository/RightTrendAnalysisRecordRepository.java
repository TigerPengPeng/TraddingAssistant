package com.autotrading.repository;

import com.autotrading.entity.RightTrendAnalysisRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface RightTrendAnalysisRecordRepository extends JpaRepository<RightTrendAnalysisRecord, Long> {

    List<RightTrendAnalysisRecord> findByGroupNameAndTradeDateOrderByCreatedAtDesc(
            String groupName, String tradeDate);

    List<RightTrendAnalysisRecord> findByStockKeyOrderByCreatedAtDesc(String stockKey);

    List<RightTrendAnalysisRecord> findByTradeDateOrderByCreatedAtDesc(String tradeDate);

    /** 同 tradeDate 同股的所有记录（按 createdAt 降序），用于 SUPERSEDED 标记 + buildTrendHistory。 */
    List<RightTrendAnalysisRecord> findByStockKeyAndTradeDateOrderByCreatedAtDesc(
            String stockKey, String tradeDate);

    /** 补偿调度器扫描：指定状态集合内、最后尝试时间早于 cutoff 的待重试记录。 */
    List<RightTrendAnalysisRecord> findByStatusInAndLastAttemptAtBefore(
            List<String> statuses, Instant cutoff);

    /** 某 tradeDate 某 stockKey 已 DONE 的记录数（用于"每股每日最多补发N封"频率控制）。 */
    long countByStockKeyAndTradeDateAndStatus(String stockKey, String tradeDate, String status);
}
