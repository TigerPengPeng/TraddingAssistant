package com.autotrading.monitor;

import com.autotrading.config.RightTrendProperties;
import com.autotrading.entity.RightTrendAnalysisRecord;
import com.autotrading.market.RightTrendAnalysisService;
import com.autotrading.market.RightTrendAnalysisService.StockTrendResult;
import com.autotrading.notification.EmailNotificationService;
import com.autotrading.repository.RightTrendAnalysisRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link RightTrendCompensationScheduler} 单测：补偿循环 + STALE 超期 + 频率控制 + 限批 + 开关。
 */
class RightTrendCompensationSchedulerTest {

    private RightTrendAnalysisService analysisService;
    private RightTrendAnalysisRecordRepository repository;
    private EmailNotificationService emailService;
    private RightTrendProperties properties;
    private RightTrendCompensationScheduler scheduler;

    @BeforeEach
    void setUp() {
        analysisService = mock(RightTrendAnalysisService.class);
        repository = mock(RightTrendAnalysisRecordRepository.class);
        emailService = mock(EmailNotificationService.class);
        properties = new RightTrendProperties();
        properties.setCompensationEnabled(true);
        properties.setCompensationBatchSize(10);
        properties.setStaleTtlDays(3);
        properties.setCompensationMaxPerDay(1);
        scheduler = new RightTrendCompensationScheduler(
                analysisService, repository, emailService, properties);
    }

    private RightTrendAnalysisRecord staleRecord(String stockKey, String tradeDate) {
        return new RightTrendAnalysisRecord("US", stockKey, "Name", tradeDate,
                false, "unknown", "unknown", "", "K线过旧", "deepseek",
                "STALE", 0, Instant.now(), null);
    }

    @Test
    @DisplayName("STALE 记录重跑成功 → 落库 + 聚合补发邮件")
    void staleRetrySuccessSendsCompensationEmail() {
        RightTrendAnalysisRecord stale = staleRecord("11.COHR", "2026-08-11");
        when(repository.findByStatusInAndLastAttemptAtBefore(anyList(), any(Instant.class)))
                .thenReturn(List.of(stale));
        StockTrendResult fixed = new StockTrendResult("11.COHR", "Coherent", "US",
                true, "high", "up", List.of("突破MA30"), "confirmed", true);
        when(analysisService.retryStock(eq(stale), anyString())).thenReturn(fixed);
        when(repository.countByStockKeyAndTradeDateAndStatus(anyString(), anyString(), eq("DONE")))
                .thenReturn(0L);

        scheduler.compensate();

        verify(repository).delete(stale);                       // 删原失败记录
        verify(analysisService).retryStock(stale, "deepseek");  // 重跑
        // 聚合补发邮件被调用（主题含「补发(补偿)」）
        ArgumentCaptor<String> subj = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendRightTrendReport(subj.capture(), any());
        assertTrue(subj.getValue().contains("补发"), "主题含「补发」");
    }

    @Test
    @DisplayName("STALE 超 staleTtlDays 未成功 → 置 FAILED，不再重试、不补发")
    void staleExpiredMarkedFailed() {
        // createdAt 在 5 天前（staleTtlDays=3 → 超期）
        RightTrendAnalysisRecord expired = new RightTrendAnalysisRecord("US", "11.GLW", "Corning",
                "2026-08-06", false, "unknown", "unknown", "", "K线过旧", "deepseek",
                "STALE", 0, Instant.now().minus(5, ChronoUnit.DAYS), null);
        when(repository.findByStatusInAndLastAttemptAtBefore(anyList(), any(Instant.class)))
                .thenReturn(List.of(expired));

        scheduler.compensate();

        // 原 update 为 FAILED（不删除、不重跑、不补发）
        ArgumentCaptor<RightTrendAnalysisRecord> captor = ArgumentCaptor.forClass(RightTrendAnalysisRecord.class);
        verify(repository).save(captor.capture());
        assertEquals("FAILED", captor.getValue().getStatus(), "超期 STALE 置 FAILED");
        verify(repository, never()).delete(any());
        verify(analysisService, never()).retryStock(any(), anyString());
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("compensationEnabled=false → 不跑任何补偿")
    void disabledDoesNothing() {
        properties.setCompensationEnabled(false);

        scheduler.compensate();

        verifyNoInteractions(repository, analysisService, emailService);
    }

    @Test
    @DisplayName("每轮最多重试 batchSize 只（防饿死调度池）")
    void respectsBatchSize() {
        properties.setCompensationBatchSize(2);
        RightTrendAnalysisRecord r1 = staleRecord("11.A", "2026-08-11");
        RightTrendAnalysisRecord r2 = staleRecord("11.B", "2026-08-11");
        RightTrendAnalysisRecord r3 = staleRecord("11.C", "2026-08-11");
        when(repository.findByStatusInAndLastAttemptAtBefore(anyList(), any(Instant.class)))
                .thenReturn(List.of(r1, r2, r3));
        when(analysisService.retryStock(any(), anyString())).thenReturn(
                new StockTrendResult("11.X", "X", "US", true, "high", "up", List.of(), "ok", true));

        scheduler.compensate();

        // 待补偿 3 只，但 batchSize=2 → 只重试 2 只
        verify(repository, times(2)).delete(any());
        verify(analysisService, times(2)).retryStock(any(), anyString());
    }

    @Test
    @DisplayName("重跑仍失败 → 不补发邮件（只发成功的聚合）")
    void retryStillFailedNoEmail() {
        RightTrendAnalysisRecord stale = staleRecord("11.COHR", "2026-08-11");
        when(repository.findByStatusInAndLastAttemptAtBefore(anyList(), any(Instant.class)))
                .thenReturn(List.of(stale));
        // 重跑仍失败（success=false）
        when(analysisService.retryStock(eq(stale), anyString())).thenReturn(
                new StockTrendResult("11.COHR", "Coherent", "US", false, "unknown", "unknown",
                        List.of(), "Analysis failed", false));

        scheduler.compensate();

        verify(repository).delete(stale);
        verify(analysisService).retryStock(stale, "deepseek");
        verifyNoInteractions(emailService);  // 无成功 → 不补发
    }

    @Test
    @DisplayName("无待补偿记录 → 空转无副作用")
    void nothingPendingNoop() {
        when(repository.findByStatusInAndLastAttemptAtBefore(anyList(), any(Instant.class)))
                .thenReturn(List.of());

        scheduler.compensate();

        verify(repository, never()).delete(any());
        verify(analysisService, never()).retryStock(any(), anyString());
        verifyNoInteractions(emailService);
    }
}
