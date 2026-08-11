package com.autotrading.notification;

import com.autotrading.market.RightTrendAnalysisService.RightTrendReport;
import com.autotrading.market.RightTrendAnalysisService.StockTrendResult;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for NotificationTemplate.rightTrendBody HTML generation.
 */
class RightTrendNotificationTemplateTest {

    @Test
    @DisplayName("Generates valid HTML with right-trend results")
    void generatesValidHtml() {
        List<StockTrendResult> stocks = List.of(
                new StockTrendResult("2.00700", "腾讯", "港股", true, "high",
                        "up", List.of("突破MA30", "成交量放大"), "confirmed uptrend", true),
                new StockTrendResult("2.09988", "阿里", "港股", false, "medium",
                        "down", List.of("仍在MA30下方"), "not yet reversed", true)
        );

        RightTrendReport report = new RightTrendReport("2025-07-13",
                List.of("港股"), stocks, System.currentTimeMillis(), "deepseek", "DeepSeek");

        String html = NotificationTemplate.rightTrendBody(report);

        assertNotNull(html);
        assertTrue(html.contains("右侧趋势分析报告"));
        assertTrue(html.contains("腾讯"));
        assertTrue(html.contains("阿里"));
        assertTrue(html.contains("1"));
        assertTrue(html.contains("突破MA30"));
        // 腾讯在账户顺序里本就先于阿里（此处也恰好是趋势股）
        int tencentPos = html.indexOf("腾讯");
        int aliPos = html.indexOf("阿里");
        assertTrue(tencentPos < aliPos, "账户顺序保持：腾讯(在前) 先于 阿里(在后)");
    }

    @Test
    @DisplayName("按 Futu 账户顺序展示，不因「右侧趋势」重排")
    void preservesAccountOrder() {
        // 账户顺序：阿里(非趋势, 在前) → 腾讯(趋势, 在后)
        // 旧的「趋势优先」会把腾讯提到阿里前；按账户顺序应保持阿里在前
        List<StockTrendResult> stocks = List.of(
                new StockTrendResult("2.09988", "阿里", "港股", false, "medium",
                        "down", List.of("仍在MA30下方"), "not yet reversed", true),
                new StockTrendResult("2.00700", "腾讯", "港股", true, "high",
                        "up", List.of("突破MA30"), "confirmed uptrend", true)
        );
        RightTrendReport report = new RightTrendReport("2025-07-13",
                List.of("港股"), stocks, System.currentTimeMillis(), "deepseek", "DeepSeek");

        String html = NotificationTemplate.rightTrendBody(report);

        int aliPos = html.indexOf("阿里");
        int tencentPos = html.indexOf("腾讯");
        assertTrue(aliPos > 0 && tencentPos > 0, "两只股票都应渲染");
        assertTrue(aliPos < tencentPos, "账户顺序：阿里(账户内在前) 应先于 腾讯(在后)，不因右侧趋势重排");
    }

    @Test
    @DisplayName("Handles empty results gracefully")
    void handlesEmptyResults() {
        RightTrendReport report = new RightTrendReport("2025-07-13",
                List.of("美股"), List.of(), System.currentTimeMillis(), "deepseek", "DeepSeek");

        String html = NotificationTemplate.rightTrendBody(report);

        assertNotNull(html);
        assertTrue(html.contains("0"));
    }

    @Test
    @DisplayName("Handles failed analysis entries")
    void handlesFailedEntries() {
        List<StockTrendResult> stocks = List.of(
                new StockTrendResult("11.FAIL", "FAIL", "美股", false, "unknown",
                        "unknown", List.of(), "Analysis failed", false)
        );

        RightTrendReport report = new RightTrendReport("2025-07-13",
                List.of("美股"), stocks, System.currentTimeMillis(), "deepseek", "DeepSeek");

        String html = NotificationTemplate.rightTrendBody(report);

        assertNotNull(html);
        // Failed entries should be filtered out from the table
        assertFalse(html.contains("FAIL"));
    }

    @Test
    @DisplayName("Volume anomaly section lists only abnormal stocks")
    void volumeAnomalySection() {
        var hot = new StockTrendResult("1.00700", "腾讯", "港股", true, "high",
                "up", List.of(), "confirmed uptrend", true, "near_top", "高位放量滞涨",
                new StockTrendResult.VolumeAnomaly(124_000_000L, 38_750_000.0, 3.2, 4.8, true));
        var normal = new StockTrendResult("11.AAPL", "Apple", "美股", false, "medium",
                "down", List.of(), "not yet reversed", true, "mid", "趋势中段",
                new StockTrendResult.VolumeAnomaly(50_000_000L, 48_000_000.0, 1.04, 0.2, false));
        RightTrendReport report = new RightTrendReport("2026-07-28", List.of("港股", "美股"),
                List.of(hot, normal), System.currentTimeMillis(), "deepseek", "DeepSeek");

        String body = NotificationTemplate.rightTrendBody(report);

        int volStart = body.indexOf("成交量异常放大");
        assertTrue(volStart >= 0, "volume section heading should be present");
        String volSection = body.substring(volStart);
        assertTrue(volSection.contains("腾讯"), "anomaly stock should be in volume section");
        assertTrue(volSection.contains("3.2×"), "ratio should be formatted as 3.2×");
        assertTrue(volSection.contains("+4.80%"), "day change should be formatted with sign");
        assertTrue(volSection.contains("放量上涨"));
        assertFalse(volSection.contains("Apple"), "non-anomaly stock must not appear in volume section");
        assertTrue(body.contains("接近顶部"), "top/bottom column should render near_top label");
    }

    @Test
    @DisplayName("右侧趋势列渲染最近7日色块条（含 MM-dd 日期与绿/红）")
    void rightTrendColumnRenders7DayStrip() {
        List<StockTrendResult.TrendDay> history = List.of(
                new StockTrendResult.TrendDay("2026-08-04", true),
                new StockTrendResult.TrendDay("2026-08-05", true),
                new StockTrendResult.TrendDay("2026-08-06", false),
                new StockTrendResult.TrendDay("2026-08-07", true)
        );
        StockTrendResult stock = new StockTrendResult(
                "2.00700", "腾讯", "港股", true, "high", "up",
                List.of("突破MA30"), "confirmed uptrend", true,
                null, null, null, history);
        RightTrendReport report = new RightTrendReport("2026-08-07",
                List.of("港股"), List.of(stock), System.currentTimeMillis(), "deepseek", "DeepSeek");

        String html = NotificationTemplate.rightTrendBody(report);

        assertTrue(html.contains("08-04") && html.contains("08-05")
                && html.contains("08-06") && html.contains("08-07"), "每个交易日 MM-dd 都渲染");
        assertTrue(html.contains("#16a34a"), "绿色块（进入右侧）");
        assertTrue(html.contains("#dc2626"), "红色块（未进入）");
        assertTrue(html.contains("<table style=\"border-collapse:collapse;margin:0 auto\">"),
                "色块条嵌套 table");
    }
}
