package com.autotrading.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RightTrendProperties.isExcluded — right-trend analysis blacklist parsing.
 */
class RightTrendPropertiesTest {

    @Test
    @DisplayName("黑名单匹配：bare code、忽略大小写、容忍逗号两侧空白")
    void matchesCaseInsensitiveWithWhitespace() {
        RightTrendProperties p = new RightTrendProperties();
        p.setExcludeCodes("BZmain, .VIX,CLmain,.SOX");

        assertTrue(p.isExcluded("BZmain"));
        assertTrue(p.isExcluded("bzmain"), "大小写不敏感");
        assertTrue(p.isExcluded(".VIX"));
        assertTrue(p.isExcluded(".sox"));
        assertFalse(p.isExcluded("AAPL"));
        assertFalse(p.isExcluded("BZ"), "不做前缀/子串匹配");
    }

    @Test
    @DisplayName("空/未配置黑名单：任何代码都不排除")
    void emptyBlacklistExcludesNothing() {
        RightTrendProperties p = new RightTrendProperties();
        assertFalse(p.isExcluded("BZmain"), "默认空字符串");

        p.setExcludeCodes("  ");
        assertFalse(p.isExcluded("BZmain"), "纯空白等同未配置");

        p.setExcludeCodes(null);
        assertFalse(p.isExcluded("BZmain"), "null 安全");

        assertFalse(p.isExcluded(null), "code 为 null 安全");
    }
}
