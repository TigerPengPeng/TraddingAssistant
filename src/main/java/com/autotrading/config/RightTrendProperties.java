package com.autotrading.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for right-trend analysis scheduling and group names.
 */
@Configuration
@ConfigurationProperties(prefix = "right-trend")
public class RightTrendProperties {

   private String groupUs = "US";
   private String groupHk = "HK";
   private String groupCn = "CN";
  private String groupWatch = "关注";
   private String groupAdd = "pool";
  private int klineLookback = 60;
    // Provider id used by the daily scheduled jobs. Independent from the
    // frontend's default-provider so changing one does not affect the other.
    private String scheduledProvider = "deepseek";
    // Volume-anomaly detection for the right-trend email's volume section.
    private double volumeAnomalyRatio = 2.0;
    private int volumeAnomalyWindow = 20;
    // 最新的 K 线 bar 距今超过该日历日数 → 视为数据过旧（兜底判据；主判据是交易日感知）
    // 1 = 只要不是上一最新交易日的数据就报错（严格模式）
    private int maxStaleDays = 1;

    // ---- 自动补偿（compensation）----
    /** 补偿调度器是否启用。 */
    private boolean compensationEnabled = true;
    /** 补偿调度器扫描间隔（ms，默认 10 分钟）。 */
    private long compensationIntervalMs = 600_000L;
    /** 每轮补偿最多重试多少只股票（防饿死 4 线程调度池）。 */
    private int compensationBatchSize = 10;
    /** STALE 记录超过该天数仍未成功 → 置 FAILED 放弃（避免下市/停牌股死循环）。 */
    private int staleTtlDays = 3;
    /** 同一股票同一 tradeDate 每日最多补发邮件数（避免轰炸）。 */
    private int compensationMaxPerDay = 1;

    public String getGroupUs() { return groupUs; }
    public void setGroupUs(String groupUs) { this.groupUs = groupUs; }
    public String getGroupHk() { return groupHk; }
    public void setGroupHk(String groupHk) { this.groupHk = groupHk; }
    public String getGroupCn() { return groupCn; }
    public void setGroupCn(String groupCn) { this.groupCn = groupCn; }
   public String getGroupWatch() { return groupWatch; }
   public void setGroupWatch(String groupWatch) { this.groupWatch = groupWatch; }
   public String getGroupAdd() { return groupAdd; }
   public void setGroupAdd(String groupAdd) { this.groupAdd = groupAdd; }
   public int getKlineLookback() { return klineLookback; }
    public void setKlineLookback(int klineLookback) { this.klineLookback = klineLookback; }
    public String getScheduledProvider() { return scheduledProvider; }
    public void setScheduledProvider(String scheduledProvider) { this.scheduledProvider = scheduledProvider; }
    public double getVolumeAnomalyRatio() { return volumeAnomalyRatio; }
    public void setVolumeAnomalyRatio(double volumeAnomalyRatio) { this.volumeAnomalyRatio = volumeAnomalyRatio; }
    public int getVolumeAnomalyWindow() { return volumeAnomalyWindow; }
    public void setVolumeAnomalyWindow(int volumeAnomalyWindow) { this.volumeAnomalyWindow = volumeAnomalyWindow; }
    public int getMaxStaleDays() { return maxStaleDays; }
    public void setMaxStaleDays(int maxStaleDays) { this.maxStaleDays = maxStaleDays; }
    public boolean isCompensationEnabled() { return compensationEnabled; }
    public void setCompensationEnabled(boolean compensationEnabled) { this.compensationEnabled = compensationEnabled; }
    public long getCompensationIntervalMs() { return compensationIntervalMs; }
    public void setCompensationIntervalMs(long compensationIntervalMs) { this.compensationIntervalMs = compensationIntervalMs; }
    public int getCompensationBatchSize() { return compensationBatchSize; }
    public void setCompensationBatchSize(int compensationBatchSize) { this.compensationBatchSize = compensationBatchSize; }
    public int getStaleTtlDays() { return staleTtlDays; }
    public void setStaleTtlDays(int staleTtlDays) { this.staleTtlDays = staleTtlDays; }
    public int getCompensationMaxPerDay() { return compensationMaxPerDay; }
    public void setCompensationMaxPerDay(int compensationMaxPerDay) { this.compensationMaxPerDay = compensationMaxPerDay; }
}
