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
    // 最新的 K 线 bar 距今超过该日历日数 → 视为数据过旧，跳过该股分析
    // （防 OpenD 数据源延迟导致用上一交易日 bar 出错误报告）
    private int maxStaleDays = 3;

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
}
