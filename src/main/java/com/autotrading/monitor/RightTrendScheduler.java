package com.autotrading.monitor;

import com.autotrading.config.RightTrendProperties;
import com.autotrading.config.AiProviderProperties;
import com.autotrading.market.RightTrendAnalysisService;
import com.autotrading.market.RightTrendAnalysisService.RightTrendReport;
import com.autotrading.notification.EmailNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * Scheduled right-trend analysis jobs.
 * - 09:00 Mon-Fri Asia/Shanghai: US stocks
 * - 17:00 Mon-Fri Asia/Shanghai: HK + CN stocks
 */
@Component
public class RightTrendScheduler {

    private static final Logger log = LoggerFactory.getLogger(RightTrendScheduler.class);
    private static final int MAX_HISTORY = 30;

    private final RightTrendAnalysisService analysisService;
    private final EmailNotificationService emailService;
    private final RightTrendProperties properties;
    private final AiProviderProperties aiProperties;
    private final LinkedList<RightTrendReport> history = new LinkedList<>();

    public RightTrendScheduler(RightTrendAnalysisService analysisService,
                                EmailNotificationService emailService,
                                RightTrendProperties properties,
                                AiProviderProperties aiProperties) {
        this.analysisService = analysisService;
        this.emailService = emailService;
        this.properties = properties;
        this.aiProperties = aiProperties;
    }

    @Scheduled(cron = "0 0 9 * * MON-FRI", zone = "Asia/Shanghai")
    public void analyzeUS() {
        log.info("Scheduled right-trend analysis: US stocks at 09:00");
        runAnalysis(List.of(properties.getGroupUs()), true, aiProperties.getDefaultProvider());
    }

    @Scheduled(cron = "0 0 17 * * MON-FRI", zone = "Asia/Shanghai")
    public void analyzeHKAndCN() {
        log.info("Scheduled right-trend analysis: HK + CN stocks at 17:00");
        runAnalysis(List.of(properties.getGroupHk(), properties.getGroupCn()), true,
                aiProperties.getDefaultProvider());
    }

    /**
    * Manual trigger (from API). Returns the generated report.
     * providerId may be null (use default) or an explicit configured provider id.
     */
    public RightTrendReport runAnalysis(List<String> groupNames, boolean sendEmail, String providerId) {
        log.info("Starting right-trend analysis for groups: {} (provider={})", groupNames, providerId);
        RightTrendReport report = analysisService.analyzeGroups(groupNames, providerId);
        storeReport(report);

        if (sendEmail) {
            String groupLabel = String.join("+", groupNames);
            long inTrend = report.stocks().stream()
                    .filter(s -> s.isInRightTrend())
                    .count();
            String subject = String.format("[右侧趋势分析] %s %s - %d/%d 进入右侧趋势",
                    report.date(), groupLabel, inTrend, report.stocks().size());
            try {
                emailService.sendRightTrendReport(subject, report);
            } catch (Exception e) {
                log.error("Failed to send right-trend email: {}", e.getMessage(), e);
            }
        }

        log.info("Right-trend report generated: {} stocks, {} in right trend",
                report.stocks().size(),
                report.stocks().stream().filter(s -> s.isInRightTrend()).count());
        return report;
    }

    private void storeReport(RightTrendReport report) {
        synchronized (history) {
            history.addFirst(report);
            while (history.size() > MAX_HISTORY) {
                history.removeLast();
            }
        }
    }

    public List<RightTrendReport> getHistory() {
        synchronized (history) {
            return new LinkedList<>(history);
        }
    }

    public RightTrendReport getLatest() {
        synchronized (history) {
            return history.isEmpty() ? null : history.getFirst();
        }
    }

    public RightTrendReport getByDate(String date) {
        synchronized (history) {
            return history.stream()
                    .filter(r -> r.date().equals(date))
                    .findFirst()
                    .orElse(null);
        }
    }
}
