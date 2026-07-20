package com.autotrading.account;

import com.autotrading.config.RightTrendProperties;
import com.autotrading.model.StockInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MarketInferenceTest {

    private final MarketInference inf = new MarketInference(newProps());

    private static RightTrendProperties newProps() {
        RightTrendProperties p = new RightTrendProperties();
        p.setGroupUs("US");
        p.setGroupHk("HK");
        p.setGroupCn("CN");
        return p;
    }

    @Test
    void usTickerLetters() {
        MarketInference.Result r = inf.infer("AAPL");
        assertTrue(r.isValid());
        assertEquals(StockInfo.MARKET_US, r.market());
        assertEquals("美股", r.marketLabel());
        assertEquals("US", r.targetGroup());
        assertEquals("AAPL", r.code());
    }

    @Test
    void usTickerLowercaseNormalized() {
        MarketInference.Result r = inf.infer(" msft ");
        assertEquals(StockInfo.MARKET_US, r.market());
        assertEquals("MSFT", r.code());
    }

    @Test
    void hkFiveDigits() {
        MarketInference.Result r = inf.infer("00700");
        assertTrue(r.isValid());
        assertEquals(StockInfo.MARKET_HK, r.market());
        assertEquals("港股", r.marketLabel());
        assertEquals("HK", r.targetGroup());
    }

    @Test
    void shanghaiByPrefix60() {
        assertEquals(StockInfo.MARKET_CN_SH, inf.infer("600519").market());
    }

    @Test
    void shanghaiStarMarket688() {
        assertEquals(StockInfo.MARKET_CN_SH, inf.infer("688981").market());
        assertEquals("CN", inf.infer("688981").targetGroup());
    }

    @Test
    void shenzhenByPrefix00() {
        assertEquals(StockInfo.MARKET_CN_SZ, inf.infer("000001").market());
        assertEquals("深市", inf.infer("000001").marketLabel());
    }

    @Test
    void shenzhenChiNextByPrefix30() {
        assertEquals(StockInfo.MARKET_CN_SZ, inf.infer("300750").market());
    }

    @Test
    void sixDigitsUnknownPrefixInvalid() {
        MarketInference.Result r = inf.infer("555555");
        assertFalse(r.isValid());
        assertNotNull(r.reason());
        assertTrue(r.reason().contains("555555"));
    }

    @Test
    void nullOrBlankInvalid() {
        assertFalse(inf.infer(null).isValid());
        assertFalse(inf.infer("   ").isValid());
        assertFalse(inf.infer("").isValid());
    }

    @Test
    void unsupportedFormatInvalid() {
        MarketInference.Result r = inf.infer("123A");
        assertFalse(r.isValid());
    }
}
