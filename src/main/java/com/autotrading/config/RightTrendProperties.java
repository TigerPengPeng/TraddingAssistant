package com.autotrading.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for right-trend analysis scheduling and group names.
 */
@Configuration
@ConfigurationProperties(prefix = "right-trend")
public class RightTrendProperties {

    private String groupUs = "美股";
    private String groupHk = "港股";
    private String groupCn = "沪深";
    private int klineLookback = 60;

    public String getGroupUs() { return groupUs; }
    public void setGroupUs(String groupUs) { this.groupUs = groupUs; }
    public String getGroupHk() { return groupHk; }
    public void setGroupHk(String groupHk) { this.groupHk = groupHk; }
    public String getGroupCn() { return groupCn; }
    public void setGroupCn(String groupCn) { this.groupCn = groupCn; }
    public int getKlineLookback() { return klineLookback; }
    public void setKlineLookback(int klineLookback) { this.klineLookback = klineLookback; }
}
