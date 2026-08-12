package com.autotrading.market;

import com.autotrading.futu.AsyncRequestBridge;
import com.autotrading.futu.FutuConnectionManager;
import com.autotrading.model.StockInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class KLineServiceFreqTest {

    private KLineService service;
    private Map<String, List<Double>> closeCache;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        FutuConnectionManager connMgr = mock(FutuConnectionManager.class);
        AsyncRequestBridge bridge = mock(AsyncRequestBridge.class);
        service = new KLineService(connMgr, bridge, 360);
        Field f = KLineService.class.getDeclaredField("closePriceCache");
        f.setAccessible(true);
        closeCache = (Map<String, List<Double>>) f.get(service);
    }

    @Test
    @DisplayName("Day closes use bare stockKey; week closes are namespaced with W: prefix")
    void testCacheNamespacing() {
        closeCache.put("11.AAPL", List.of(100.0, 101.0, 102.0));
        closeCache.put("W:11.AAPL", List.of(90.0, 95.0, 98.0));

        // Day frequency (default) reads bare key
        assertEquals(List.of(100.0, 101.0, 102.0), service.getCloses("11.AAPL"));
        assertEquals(List.of(100.0, 101.0, 102.0), service.getCloses("11.AAPL", "day"));

        // Week frequency reads namespaced key
        assertEquals(List.of(90.0, 95.0, 98.0), service.getCloses("11.AAPL", "week"));
    }

    @Test
    @DisplayName("Unknown frequency falls back to day")
    void testUnknownFrequencyDefaultsToDay() {
        closeCache.put("11.AAPL", List.of(100.0));
        // "monthly" is not supported -> should fall back to day
        assertEquals(List.of(100.0), service.getCloses("11.AAPL", "monthly"));
    }

    @Test
    @DisplayName("Missing frequency returns empty list, not null")
    void testMissingReturnsEmpty() {
        assertTrue(service.getCloses("11.UNKNOWN", "week").isEmpty());
        assertTrue(service.getCloses("11.UNKNOWN").isEmpty());
    }

    @Test
    @DisplayName("isRateLimited 正确识别 OpenD 限流响应（high frequency / Maximum per）")
    void testIsRateLimited() {
        // OpenD 限流原文："...high frequency. Maximum 60 times per 30 seconds."
        assertTrue(KLineService.isRateLimited("Get Historical Candlestick request failed due to high frequency. Maximum 60 times per 30 seconds."));
        assertTrue(KLineService.isRateLimited("high frequency"));
        assertTrue(KLineService.isRateLimited("Maximum 99 times per 60 seconds"));
        // 非限流错误不应识别为限流（避免无权限/不支持等也触发重试）
        assertFalse(KLineService.isRateLimited("Insufficient quote permission. Please go to the Quote Store to purchase a quote card."));
        assertFalse(KLineService.isRateLimited("US stock indices are not supported"));
        assertFalse(KLineService.isRateLimited(null));
        assertFalse(KLineService.isRateLimited(""));
    }
}
