package com.autotrading.account;

import com.autotrading.config.RightTrendProperties;
import com.autotrading.model.StockInfo;
import org.springframework.stereotype.Component;

/**
 * Infers a stock's market and target Futu user-security group from its code.
 * <p>
 * Rules (Futu QotCommon market + common listing conventions):
 * <ul>
 *   <li>Pure letters (1-6 chars) -> US stock (market 11) -> {@code right-trend.group-us}</li>
 *   <li>5 digits -> HK stock (market 1) -> {@code right-trend.group-hk}</li>
 *   <li>6 digits starting with 60/68/90 -> Shanghai A-share (market 21) -> {@code right-trend.group-cn}</li>
 *   <li>6 digits starting with 00/30/20 -> Shenzhen A-share (market 22) -> {@code right-trend.group-cn}</li>
 *   <li>Anything else -> unknown (caller rejects)</li>
 * </ul>
 * The market_hint from the vision model is advisory only: the code format is
 * authoritative, so a mislabelled hint never writes to the wrong group.
 */
@Component
public class MarketInference {

    private final RightTrendProperties properties;

    public MarketInference(RightTrendProperties properties) {
        this.properties = properties;
    }

    public Result infer(String rawCode) {
        if (rawCode == null) {
            return Result.invalid("代码为空");
        }
        String code = rawCode.trim().toUpperCase();
        if (code.isEmpty()) {
            return Result.invalid("代码为空");
        }

        if (code.matches("^[A-Z]{1,6}$")) {
            return new Result(StockInfo.MARKET_US, "美股", groupUs(), code, null);
        }
        if (code.matches("^[0-9]{5}$")) {
            return new Result(StockInfo.MARKET_HK, "港股", groupHk(), code, null);
        }
        if (code.matches("^[0-9]{6}$")) {
            String p = code.substring(0, 2);
            String p3 = code.substring(0, 3);
            // Shanghai: 60xxxx, 68xxxx (incl. 688 STAR), 900xxx B-shares
            if (p.equals("60") || p.equals("68") || p3.equals("900")) {
                return new Result(StockInfo.MARKET_CN_SH, "沪市", groupCn(), code, null);
            }
            // Shenzhen: 00xxxx, 30xxxx (ChiNext), 200xxx B-shares
            if (p.equals("00") || p.equals("30") || p3.equals("200")) {
                return new Result(StockInfo.MARKET_CN_SZ, "深市", groupCn(), code, null);
            }
            return Result.invalid("无法识别的 6 位 A 股代码: " + code);
        }
        return Result.invalid("代码格式不支持: " + code);
    }

    private String groupUs() { return properties.getGroupUs(); }
    private String groupHk() { return properties.getGroupHk(); }
    private String groupCn() { return properties.getGroupCn(); }

    /**
     * Inferred market + group. {@code market == -1} means invalid;
     * {@code reason} is non-null only when invalid.
     */
    public record Result(int market, String marketLabel, String targetGroup, String code, String reason) {
        public boolean isValid() { return market > 0 && targetGroup != null && !targetGroup.isBlank(); }
        public static Result invalid(String reason) {
            return new Result(-1, null, null, null, reason);
        }
    }
}
