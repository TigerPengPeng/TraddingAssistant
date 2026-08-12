package com.autotrading.monitor;

import com.autotrading.config.RightTrendProperties;
import com.autotrading.entity.RightTrendAnalysisRecord;
import com.autotrading.market.RightTrendAnalysisService;
import com.autotrading.market.RightTrendAnalysisService.RightTrendReport;
import com.autotrading.market.RightTrendAnalysisService.StockTrendResult;
import com.autotrading.notification.EmailNotificationService;
import com.autotrading.repository.RightTrendAnalysisRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 右侧趋势分析自动补偿调度器。
 *
 * <p>周期回扫 STALE / RATE_LIMITED / ANALYSIS_FAILED 状态的记录，重新分析；
 * OpenD 数据就绪后自动修正（如 Coherent 8/10 bar 次日就绪）。成功的结果聚合成
 * 「右侧趋势-补发(补偿)」邮件，同一 tradeDate 每日最多补发 1 次。
 *
 * <p>线程池：fixedDelay（上轮跑完才开始下轮）+ 每轮限 batchSize 只，防饿死 4 线程调度池。
 */
@Component
public class RightTrendCompensationScheduler {

    private static final Logger log = LoggerFactory.getLogger(RightTrendCompensationScheduler.class);
    private static final List<String> COMPENSABLE_STATUSES =
            List.of("STALE", "RATE_LIMITED", "ANALYSIS_FAILED");

    private final RightTrendAnalysisService analysisService;
    private final RightTrendAnalysisRecordRepository repository;
    private final EmailNotificationService emailService;
    private final RightTrendProperties properties;

    public RightTrendCompensationScheduler(RightTrendAnalysisService analysisService,
                                             RightTrendAnalysisRecordRepository repository,
                                             EmailNotificationService emailService,
                                             RightTrendProperties properties) {
        this.analysisService = analysisService;
        this.repository = repository;
        this.emailService = emailService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${right-trend.compensation.interval-ms:600000}")
    public void compensate() {
        if (!properties.isCompensationEnabled()) return;

        List<RightTrendAnalysisRecord> pending = repository.findByStatusInAndLastAttemptAtBefore(
                COMPENSABLE_STATUSES, Instant.now());
        if (pending.isEmpty()) return;

        int batchSize = properties.getCompensationBatchSize();
        // 优先重试 RATE_LIMITED / ANALYSIS_FAILED（瞬时错误，几秒-分钟可恢复），STALE 排后
        List<RightTrendAnalysisRecord> batch = pickBatch(pending, batchSize);
        log.info("Compensation round: {} pending, retrying {} this round", pending.size(), batch.size());

        List<StockTrendResult> newlyFixed = new ArrayList<>();
        String providerId = properties.getScheduledProvider();

        for (RightTrendAnalysisRecord rec : batch) {
            try {
                // STALE 超期 → 置 FAILED 放弃（避免下市/停牌死循环）
                if ("STALE".equals(rec.getStatus())
                        && isStaleExpired(rec)) {
                    log.warn("STALE record {} expired (>{} days), marking FAILED",
                            rec.getStockKey(), properties.getStaleTtlDays());
                    rec.setStatus("FAILED");
                    rec.setErrorMessage("STALE超期(" + properties.getStaleTtlDays() + "天)放弃");
                    repository.save(rec);
                    continue;
                }
                // 删除原失败记录，重试时重新 insert（成功 → DONE + 旧 DONE SUPERSEDED）
                repository.delete(rec);
                StockTrendResult result = analysisService.retryStock(rec, providerId);
                if (result.success()) {
                    newlyFixed.add(result);
                }
            } catch (Exception e) {
                log.error("Compensation retry failed for {}: {}", rec.getStockKey(), e.getMessage(), e);
            }
        }

        if (!newlyFixed.isEmpty()) {
            sendCompensationEmail(newlyFixed, providerId);
        }
        log.info("Compensation round done: {} fixed, {} batched", newlyFixed.isEmpty() ? 0 : countUnique(newlyFixed), batch.size());
    }

    /** RATE_LIMITED/ANALYSIS_FAILED 优先；STALE 次之；截 batchSize。 */
    private List<RightTrendAnalysisRecord> pickBatch(List<RightTrendAnalysisRecord> pending, int batchSize) {
        List<RightTrendAnalysisRecord> priority = new ArrayList<>();
        List<RightTrendAnalysisRecord> stale = new ArrayList<>();
        for (RightTrendAnalysisRecord r : pending) {
            if ("STALE".equals(r.getStatus())) stale.add(r);
            else priority.add(r);
        }
        List<RightTrendAnalysisRecord> batch = new ArrayList<>(priority);
        batch.addAll(stale);
        return batch.size() > batchSize ? batch.subList(0, batchSize) : batch;
    }

    /** STALE 超期判断：自上次尝试时间（fallback 创建时间）起超过 staleTtlDays → 放弃。 */
    private boolean isStaleExpired(RightTrendAnalysisRecord rec) {
        Instant ref = rec.getLastAttemptAt() != null ? rec.getLastAttemptAt() : rec.getCreatedAt();
        long ageDays = ChronoUnit.DAYS.between(ref, Instant.now());
        return ageDays > properties.getStaleTtlDays();
    }

    /** 聚合补发：同一 tradeDate 每日最多 1 封（去重）。 */
    private void sendCompensationEmail(List<StockTrendResult> fixed, String providerId) {
        // 按 tradeDate 分组（result 不带 tradeDate，但 fixed 同轮多为同日；用 LocalDate.now 兜底分组）
        // 实际：retryStock 的 result.groupName 含分组；tradeDate 用今天作为补发归属日
        String tradeDate = LocalDate.now().toString();
        Set<String> alreadySentToday = new HashSet<>();
        List<StockTrendResult> toSend = new ArrayList<>();
        for (StockTrendResult r : fixed) {
            String dedup = r.stockKey() + "|" + tradeDate;
            // 每股每日最多 compensationMaxPerDay 封：查今日已 DONE 数
            long doneToday = repository.countByStockKeyAndTradeDateAndStatus(
                    r.stockKey(), tradeDate, "DONE");
            if (doneToday > properties.getCompensationMaxPerDay() || alreadySentToday.contains(dedup)) {
                continue;
            }
            alreadySentToday.add(dedup);
            toSend.add(r);
        }
        if (toSend.isEmpty()) return;

        Set<String> groups = new HashSet<>();
        for (StockTrendResult r : toSend) groups.add(r.groupName());
        long inTrend = toSend.stream().filter(StockTrendResult::isInRightTrend).count();
        RightTrendReport report = new RightTrendReport(
                tradeDate, new ArrayList<>(groups), toSend, System.currentTimeMillis(),
                providerId, null);
        String subject = String.format("[右侧趋势-补发(补偿)] %s %s - %d/%d 进入右侧趋势",
                tradeDate, String.join("+", groups), inTrend, toSend.size());
        try {
            emailService.sendRightTrendReport(subject, report);
            log.info("Compensation email sent: {} stocks", toSend.size());
        } catch (Exception e) {
            log.error("Failed to send compensation email: {}", e.getMessage(), e);
        }
    }

    private int countUnique(List<StockTrendResult> fixed) {
        return (int) fixed.stream().map(StockTrendResult::stockKey).distinct().count();
    }
}
