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
    private int klineLookback = 60;

    public String getGroupUs() { return groupUs; }
    public void setGroupUs(String groupUs) { this.groupUs = groupUs; }
    public String getGroupHk() { return groupHk; }
    public void setGroupHk(String groupHk) { this.groupHk = groupHk; }
    public String getGroupCn() { return groupCn; }
    public void setGroupCn(String groupCn) { this.groupCn = groupCn; }
    public String getGroupWatch() { return groupWatch; }
    public void setGroupWatch(String groupWatch) { this.groupWatch = groupWatch; }
    public int getKlineLookback() { return klineLookback; }
    public void setKlineLookback(int klineLookback) { this.klineLookback = klineLookback; }
}
