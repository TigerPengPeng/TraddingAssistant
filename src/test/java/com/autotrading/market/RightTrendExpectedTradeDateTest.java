package com.autotrading.market;

import com.autotrading.model.StockInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for RightTrendAnalysisService.expectedLatestTradeDateAt — market-aware
 * "latest completed trade date" used by the stale-bar check. 2026-08-12=Wed,
 * 08-13=Thu, 08-14=Fri, 08-15=Sat, 08-16=Sun, 08-17=Mon. August = EDT (UTC-4).
 */
class RightTrendExpectedTradeDateTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final ZoneId CST = ZoneId.of("Asia/Shanghai");

    private static ZonedDateTime et(int y, int m, int d, int hh, int mm) {
        return ZonedDateTime.of(y, m, d, hh, mm, 0, 0, ET);
    }

    @Test
    @DisplayName("美股定时任务时刻：北京 08-14 09:00 = 美东 08-13 21:00（收盘后）→ 预期 08-13")
    void usScheduledRunExpectsPreviousEtDay() {
        assertEquals("2026-08-13",
                RightTrendAnalysisService.expectedLatestTradeDateAt(StockInfo.MARKET_US, et(2026, 8, 13, 21, 0)));
    }

    @Test
    @DisplayName("美股手动触发：北京 08-14 15:00 = 美东 08-14 03:00（收盘前）→ 预期 08-13，不是北京当天 08-14")
    void usBeforeEtCloseExpectsPreviousDay() {
        assertEquals("2026-08-13",
                RightTrendAnalysisService.expectedLatestTradeDateAt(StockInfo.MARKET_US, et(2026, 8, 14, 3, 0)));
    }

    @Test
    @DisplayName("美股盘前（美东 08-14 07:40，当日未开盘）→ 预期 08-13")
    void usPreMarketExpectsPreviousDay() {
        assertEquals("2026-08-13",
                RightTrendAnalysisService.expectedLatestTradeDateAt(StockInfo.MARKET_US, et(2026, 8, 14, 7, 40)));
    }

    @Test
    @DisplayName("美股周末：美东周六 → 预期周五；美东周一凌晨 → 预期周五")
    void usWeekendFallsBackToFriday() {
        assertEquals("2026-08-14",
                RightTrendAnalysisService.expectedLatestTradeDateAt(StockInfo.MARKET_US, et(2026, 8, 15, 12, 0)));
        assertEquals("2026-08-14",
                RightTrendAnalysisService.expectedLatestTradeDateAt(StockInfo.MARKET_US, et(2026, 8, 16, 20, 0)));
        assertEquals("2026-08-14",
                RightTrendAnalysisService.expectedLatestTradeDateAt(StockInfo.MARKET_US, et(2026, 8, 17, 3, 0)));
    }

    @Test
    @DisplayName("A股/港股：服务器本地（中国时区）当天为工作日 → 预期当天；周六 → 预期周五")
    void cnHkUseLocalDate() {
        assertEquals("2026-08-14",
                RightTrendAnalysisService.expectedLatestTradeDateAt(StockInfo.MARKET_CN_SH,
                        ZonedDateTime.of(2026, 8, 14, 17, 0, 0, 0, CST)));
        assertEquals("2026-08-14",
                RightTrendAnalysisService.expectedLatestTradeDateAt(StockInfo.MARKET_HK,
                        ZonedDateTime.of(2026, 8, 15, 12, 0, 0, 0, CST)));
    }
}
