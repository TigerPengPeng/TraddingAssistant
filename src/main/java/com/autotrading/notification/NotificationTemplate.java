package com.autotrading.notification;

import com.autotrading.model.Direction;
import com.autotrading.model.MAEvent;
import com.autotrading.model.StockInfo;

import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Generates email subject and HTML body for alert events.
 */
public class NotificationTemplate {

    /** DTO for MA breakdown scan report rows. */
    public record MABreakdownItem(String stockKey, String stockName, double currentPrice,
                                   java.util.List<Integer> brokenPeriods,
                                   java.util.Map<Integer, Double> maValues) {}

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final String GREEN = "#16a34a";
    private static final String RED = "#dc2626";

    private NotificationTemplate() {}

    // ---- MA Event ----

    public static String maEventSubject(MAEvent event) {
        String action = event.getDirection() == Direction.BREAK_UP ? "突破" : "跌破";
        return String.format("[告警] %s(%s) %s MA%d(%s)",
                event.getStockName(), event.getStockKey(), action, event.getMaPeriod(),
                freqLabel(event.getFrequency()));
    }

    public static String maEventBody(MAEvent event) {
        String color = event.getDirection() == Direction.BREAK_UP ? GREEN : RED;
        String action = event.getDirection().getLabel();
        String[] parts = event.getStockKey().split("\\.");
        String marketLabel = marketLabel(parts.length > 0 ? Integer.parseInt(parts[0]) : 0);

        // Use StringBuilder to avoid String.format treating row() outputs
        // (which contain literal % chars from width:30%) as format specifiers.
        StringBuilder sb = new StringBuilder();
        sb.append("<h2 style=\"color:").append(color).append("\">")
          .append(action).append(" MA").append(event.getMaPeriod())
          .append("(").append(freqLabel(event.getFrequency())).append(")").append("</h2>");
        sb.append("<table style=\"border-collapse:collapse;width:100%;font-size:14px\">");
        sb.append(row("股票", event.getStockName() + " (" + event.getStockKey() + ")"));
        sb.append(colorRow("事件", action + " MA" + event.getMaPeriod()
                + "(" + freqLabel(event.getFrequency()) + ")", color));
        sb.append(colorRow("当前价", formatPrice(event.getPrice()), color));
        sb.append(row("MA" + event.getMaPeriod(), formatPrice(event.getMaValue())));
        sb.append(row("交易时段", event.getSession().getLabel()));
        sb.append(row("市场", marketLabel));
        sb.append(row("时间", TS_FMT.format(Instant.ofEpochMilli(event.getTimestamp()))));
        sb.append("</table>");
        return htmlWrap(sb.toString());
    }

    // ---- MA Event Batch (5-minute digest) ----

    public static String maBatchBody(java.util.List<MAEvent> events) {
        StringBuilder sb = new StringBuilder();
        sb.append("<h2 style=\"color:#58a6ff\">MA 事件汇总</h2>");
        sb.append("<p style=\"color:#8b949e;font-size:13px;margin-bottom:16px\">")
          .append("近 5 分钟共 ").append(events.size()).append(" 条 MA 事件</p>");
        sb.append("<table style=\"border-collapse:collapse;width:100%;font-size:13px\">");
        sb.append("<tr><th style=\"padding:8px 10px;border:1px solid #e5e7eb;background:#f3f4f6;text-align:left\">股票</th>")
          .append("<th style=\"padding:8px 10px;border:1px solid #e5e7eb;background:#f3f4f6;text-align:left\">事件</th>")
          .append("<th style=\"padding:8px 10px;border:1px solid #e5e7eb;background:#f3f4f6\">当前价</th>")
          .append("<th style=\"padding:8px 10px;border:1px solid #e5e7eb;background:#f3f4f6\">均线值</th>")
          .append("<th style=\"padding:8px 10px;border:1px solid #e5e7eb;background:#f3f4f6\">时间</th></tr>");
        for (MAEvent event : events) {
            String color = event.getDirection() == Direction.BREAK_UP ? GREEN : RED;
            sb.append("<tr>")
              .append("<td style=\"padding:8px 10px;border:1px solid #e5e7eb;font-weight:600\">")
              .append(event.getStockName()).append(" <span style=\"color:#8b949e;font-size:12px\">")
              .append(event.getStockKey()).append("</span></td>")
              .append("<td style=\"padding:8px 10px;border:1px solid #e5e7eb;color:").append(color).append(";font-weight:600\">")
              .append(event.getDirection().getLabel()).append(" MA").append(event.getMaPeriod())
              .append("(").append(freqLabel(event.getFrequency())).append(")").append("</td>")
              .append("<td style=\"padding:8px 10px;border:1px solid #e5e7eb;text-align:center\">")
              .append(formatPrice(event.getPrice())).append("</td>")
              .append("<td style=\"padding:8px 10px;border:1px solid #e5e7eb;text-align:center\">")
              .append(formatPrice(event.getMaValue())).append("</td>")
              .append("<td style=\"padding:8px 10px;border:1px solid #e5e7eb;text-align:center\">")
              .append(TS_FMT.format(Instant.ofEpochMilli(event.getTimestamp()))).append("</td>")
              .append("</tr>");
        }
        sb.append("</table>");
        return htmlWrap(sb.toString());
    }

    // ---- Daily Risk Report ----

    public static String riskReportBody(String marketLabel, String dateStr,
                                         java.util.List<RiskReportItem> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("<h2 style=\"color:#dc2626\">")
          .append(marketLabel).append(" 当日风险股票汇总</h2>");
        sb.append("<p style=\"color:#8b949e;font-size:13px;margin-bottom:16px\">数据日期: ")
          .append(dateStr).append("</p>");

        if (items.isEmpty()) {
            sb.append("<p style=\"padding:20px;background:#f9fafb;border-radius:8px;text-align:center\">")
              .append("今日无高风险股票，市场整体平稳</p>");
        } else {
            sb.append("<table style=\"border-collapse:collapse;width:100%;font-size:13px\">");
            sb.append("<tr><th style=\"padding:8px 10px;border:1px solid #e5e7eb;background:#f3f4f6;text-align:left\">股票</th>")
              .append("<th style=\"padding:8px 10px;border:1px solid #e5e7eb;background:#f3f4f6\">风险分</th>")
              .append("<th style=\"padding:8px 10px;border:1px solid #e5e7eb;background:#f3f4f6\">等级</th>")
              .append("<th style=\"padding:8px 10px;border:1px solid #e5e7eb;background:#f3f4f6\">涨跌%</th>")
              .append("<th style=\"padding:8px 10px;border:1px solid #e5e7eb;background:#f3f4f6;text-align:left\">风险因素</th></tr>");

            for (RiskReportItem item : items) {
                String levelColor = item.highRisk() ? RED : "#d29922";
                String levelText = item.highRisk() ? "高风险" : "中等";
                String changeColor = item.changeRate() >= 0 ? GREEN : RED;
                String changeStr = String.format("%s%.2f%%",
                        item.changeRate() >= 0 ? "+" : "", item.changeRate());

                sb.append("<tr>")
                  .append("<td style=\"padding:8px 10px;border:1px solid #e5e7eb;font-weight:600\">")
                  .append(item.stockName()).append(" <span style=\"color:#8b949e;font-size:12px\">")
                  .append(item.stockKey()).append("</span></td>")
                  .append("<td style=\"padding:8px 10px;border:1px solid #e5e7eb;text-align:center;font-weight:700;color:")
                  .append(levelColor).append("\">").append(item.score()).append("</td>")
                  .append("<td style=\"padding:8px 10px;border:1px solid #e5e7eb;text-align:center;color:")
                  .append(levelColor).append(";font-weight:600\">").append(levelText).append("</td>")
                  .append("<td style=\"padding:8px 10px;border:1px solid #e5e7eb;text-align:center;color:")
                  .append(changeColor).append(";font-weight:600\">").append(changeStr).append("</td>")
                  .append("<td style=\"padding:8px 10px;border:1px solid #e5e7eb;font-size:12px\">")
                  .append(String.join("、", item.riskFactors())).append("</td>")
                  .append("</tr>");
            }
            sb.append("</table>");
        }

        sb.append("<p style=\"margin-top:16px;font-size:12px;color:#8b949e\">")
          .append("风险分越高表示风险越大。60分以上为高风险，30-59为中等风险。")
          .append("</p>");

        return htmlWrap(sb.toString());
    }

    public record RiskReportItem(String stockKey, String stockName, int score, boolean highRisk,
                                 double changeRate, java.util.List<String> riskFactors) {}

    // ---- Helpers ----
    // ---- Fluctuation Batch Email ----

    public static String fluctuationBatchBody(String timeStr, String logic,
            java.util.List<com.autotrading.monitor.TimeWindowFluctuationMonitor.StockFluctuationResult> results) {
        StringBuilder sb = new StringBuilder();
        String logicLabel = "AND".equalsIgnoreCase(logic) ? "全部满足" : "任一满足";
        sb.append("<h2 style=\"color:#58a6ff\">").append("盘中波动汇总: ").append(results.size()).append(" 只股票触发规则")
          .append("</h2>");
        sb.append("<p style=\"color:#8b949e;font-size:13px;margin-bottom:16px\">")
          .append("时间: ").append(timeStr)
          .append(" | 规则逻辑: ").append(logicLabel).append("</p>");

        sb.append("<table style=\"border-collapse:collapse;width:100%;font-size:13px\">");
        sb.append("<tr><th style=\"padding:8px 10px;border:1px solid #e5e7eb;background:#f3f4f6;text-align:left\">股票</th>")
          .append("<th style=\"padding:8px 10px;border:1px solid #e5e7eb;background:#f3f4f6\">当前价</th>")
          .append("<th style=\"padding:8px 10px;border:1px solid #e5e7eb;background:#f3f4f6\">方向</th>")
          .append("<th style=\"padding:8px 10px;border:1px solid #e5e7eb;background:#f3f4f6;text-align:left\">匹配规则</th></tr>");

        for (var r : results) {
            String dirColor = "涨".equals(r.direction()) ? GREEN : RED;
            StringBuilder ruleDetails = new StringBuilder();
            for (var rm : r.allRules()) {
                if (ruleDetails.length() > 0) ruleDetails.append("<br>");
                String mark = rm.matched() ? "\u2714" : "\u2718";
                String color = rm.matched() ? GREEN : "#8b949e";
                ruleDetails.append("<span style=\"color:").append(color).append("\">")
                  .append(mark).append(" </span>")
                  .append(rm.rule().getWindowMinutes()).append("min >= ")
                  .append(String.format("%.1f", rm.rule().getThresholdPercent())).append("%")
                  .append(" (").append(String.format("%+.2f%%", rm.changePct())).append(")");
            }
            sb.append("<tr>")
              .append("<td style=\"padding:8px 10px;border:1px solid #e5e7eb;font-weight:600\">")
              .append(r.stockName()).append(" <span style=\"color:#8b949e;font-size:12px\">")
              .append(r.stockKey()).append("</span></td>")
              .append("<td style=\"padding:8px 10px;border:1px solid #e5e7eb;text-align:center\">")
              .append(formatPrice(r.currentPrice())).append("</td>")
              .append("<td style=\"padding:8px 10px;border:1px solid #e5e7eb;text-align:center;color:")
              .append(dirColor).append(";font-weight:600\">").append(r.direction()).append("</td>")
              .append("<td style=\"padding:8px 10px;border:1px solid #e5e7eb;font-size:12px\">")
              .append(ruleDetails).append("</td>")
              .append("</tr>");
        }
        sb.append("</table>");
        return htmlWrap(sb.toString());
    }

    // ---- MA Breakdown Scan Report ----

    public static String maBreakdownBody(String timeStr, java.util.List<MABreakdownItem> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("<h2 style=\"color:#dc2626\">MA 均线破位扫描</h2>");
        sb.append("<p style=\"color:#8b949e;font-size:13px;margin-bottom:16px\">时间: ").append(timeStr)
          .append(" | 扫描到 ").append(items.size()).append(" 只股票破位</p>");

        if (items.isEmpty()) {
            sb.append("<p style=\"padding:20px;background:#f9fafb;border-radius:8px;text-align:center\">")
              .append("当前无股票跌破任何 MA 均线</p>");
        } else {
            sb.append("<table style=\"border-collapse:collapse;width:100%;font-size:13px\">");
            sb.append("<tr><th style=\"padding:8px 10px;border:1px solid #e5e7eb;background:#f3f4f6;text-align:left\">股票</th>")
              .append("<th style=\"padding:8px 10px;border:1px solid #e5e7eb;background:#f3f4f6\">当前价</th>")
              .append("<th style=\"padding:8px 10px;border:1px solid #e5e7eb;background:#f3f4f6;text-align:left\">跌破均线</th></tr>");

            for (MABreakdownItem item : items) {
                StringBuilder periods = new StringBuilder();
                for (int p : item.brokenPeriods()) {
                    if (periods.length() > 0) periods.append(" ");
                    Double maVal = item.maValues().get(p);
                    periods.append("<span style=\"display:inline-block;margin:2px 4px;padding:2px 8px;border-radius:10px;background:rgba(248,81,73,0.15);color:#dc2626;font-weight:600\">")
                      .append("MA").append(p)
                      .append(" (").append(formatPrice(maVal)).append(")</span>");
                }
                sb.append("<tr>")
                  .append("<td style=\"padding:8px 10px;border:1px solid #e5e7eb;font-weight:600\">")
                  .append(item.stockName()).append(" <span style=\"color:#8b949e;font-size:12px\">")
                  .append(item.stockKey()).append("</span></td>")
                  .append("<td style=\"padding:8px 10px;border:1px solid #e5e7eb;text-align:center\">")
                  .append(formatPrice(item.currentPrice())).append("</td>")
                  .append("<td style=\"padding:8px 10px;border:1px solid #e5e7eb\">")
                  .append(periods).append("</td>")
                  .append("</tr>");
            }
            sb.append("</table>");
        }
        return htmlWrap(sb.toString());
    }


    // ---- Right Trend Report ----

    public static String rightTrendBody(com.autotrading.market.RightTrendAnalysisService.RightTrendReport report) {
        StringBuilder sb = new StringBuilder();
        String groupLabel = String.join("+", report.groupNames());
        long inTrend = report.stocks().stream().filter(s -> s.isInRightTrend()).count();

        sb.append("<h2 style=\"color:#16a34a\">")
          .append("右侧趋势分析报告 - ").append(report.date()).append(" ").append(groupLabel);
        if (report.providerLabel() != null && !report.providerLabel().isBlank()) {
            sb.append(" <span style=\"color:#6b7280\">· ").append(report.providerLabel()).append("</span>");
        }
        sb.append("</h2>");
        sb.append("<p style=\"padding:12px 16px;background:#1a1a2e;border-radius:8px;color:#e6edf3;font-size:14px;margin-bottom:16px\">")
          .append("<strong>汇总: </strong>共分析 ").append(report.stocks().size())
          .append(" 只股票，其中 <span style=\"color:#16a34a;font-weight:700\">").append(inTrend)
          .append("</span> 只已进入右侧趋势</p>");

        // 保持 Futu 账户自选股顺序：report.stocks() 已是账户顺序
        //（analyzeGroups 按 getStocksInGroup 返回顺序追加）。不再过滤失败条目——
        // K线拉取失败/数据过旧的股保留在表中（淡红底 + 红字 reason），避免静默丢失。
        var listed = report.stocks();

        if (listed.isEmpty()) {
            sb.append("<p style=\"padding:20px;background:#f9fafb;border-radius:8px;text-align:center\">")
              .append("本次分析无有效结果</p>");
        } else {
            sb.append("<table style=\"border-collapse:collapse;width:100%;font-size:13px\">");
            sb.append("<tr><th style=\"padding:8px 10px;border:1px solid #e5e7eb;background:#f3f4f6;text-align:left\">股票</th>")
              .append("<th style=\"padding:8px 10px;border:1px solid #e5e7eb;background:#f3f4f6\">分组</th>")
              .append("<th style=\"padding:8px 10px;border:1px solid #e5e7eb;background:#f3f4f6\">右侧趋势</th>")
              .append("<th style=\"padding:8px 10px;border:1px solid #e5e7eb;background:#f3f4f6\">涨跌幅</th>")
              .append("<th style=\"padding:8px 10px;border:1px solid #e5e7eb;background:#f3f4f6\">置信度</th>")
              .append("<th style=\"padding:8px 10px;border:1px solid #e5e7eb;background:#f3f4f6\">顶/底</th>")
              .append("<th style=\"padding:8px 10px;border:1px solid #e5e7eb;background:#f3f4f6;text-align:left\">关键信号</th>")
              .append("<th style=\"padding:8px 10px;border:1px solid #e5e7eb;background:#f3f4f6;text-align:left\">分析原因</th></tr>");

            for (var stock : listed) {
                boolean ok = stock.success();
                // 失败股（K线拉取失败/数据过旧）也渲染历史成功数据的色块条，仅末尾追加一格灰色 ⚠ 异常格
                String trendCell = trendStripHtml(stock.trendHistory(), stock.isInRightTrend(), !ok);
                String confColor = switch (stock.confidence()) {
                    case "high" -> "#16a34a";
                    case "medium" -> "#d29922";
                    default -> "#8b949e";
                };
                // 失败股（K线拉取失败/数据过旧）整行淡红底，非 reason 列显示 "-"
                String rowBg = ok ? "" : "background:#fef2f2;";
                String td = "padding:8px 10px;border:1px solid #e5e7eb;" + rowBg;

                sb.append("<tr>")
                  .append("<td style=\"").append(td).append("font-weight:600\">")
                  .append(stock.stockName())
                  .append(" <span style=\"color:#8b949e;font-size:12px\">")
                  .append(stock.stockKey()).append("</span></td>")
                  .append("<td style=\"").append(td).append("text-align:center\">")
                  .append(stock.groupName()).append("</td>")
                  .append("<td style=\"").append(td).append("text-align:center\">")
                  .append(trendCell).append("</td>")
                  .append("<td style=\"").append(td).append("text-align:center;font-weight:600;color:")
                  .append(dayChangeColor(stock)).append("\">").append(dayChangeText(stock)).append("</td>")
                  .append("<td style=\"").append(td).append("text-align:center;color:")
                  .append(confColor).append(";font-weight:600\">").append(ok ? stock.confidence() : "-").append("</td>")
                  .append("<td style=\"").append(td).append("text-align:center\">")
                  .append(ok ? topBottomCell(stock.topBottomSignal(), stock.topBottomReason()) : "-").append("</td>")
                  .append("<td style=\"").append(td).append("font-size:12px\">")
                  .append(ok && !stock.keySignals().isEmpty() ? String.join("、", stock.keySignals()) : "-")
                  .append("</td>")
                  .append("<td style=\"").append(td).append("font-size:12px;color:")
                  .append(ok ? "" : "#dc2626").append("\">")
                  .append(stock.reason()).append("</td>")
                  .append("</tr>");
            }
            sb.append("</table>");
        }

        // ---- Volume anomaly section (recent trading day) ----
        var volAnomalies = report.stocks().stream()
                .filter(s -> s.success() && s.volume() != null && s.volume().anomaly())
                .sorted((a, b) -> Double.compare(b.volume().ratio(), a.volume().ratio()))
                .toList();
        sb.append("<h3 style=\"color:#d29922;margin-top:24px\">⚠ 成交量异常放大（最近交易日）</h3>");
        if (volAnomalies.isEmpty()) {
            sb.append("<p style=\"padding:16px;background:#f9fafb;border-radius:8px;text-align:center;color:#8b949e\">")
              .append("最近交易日无成交量异常放大的股票</p>");
        } else {
            sb.append("<p style=\"padding:12px 16px;background:#1a1a2e;border-radius:8px;color:#e6edf3;font-size:14px;margin-bottom:16px\">")
              .append("共 <strong style=\"color:#d29922\">").append(volAnomalies.size())
              .append("</strong> 只股票成交量异常放大</p>");
            sb.append("<table style=\"border-collapse:collapse;width:100%;font-size:13px\">");
            sb.append("<tr>")
              .append("<th style=\"padding:8px 10px;border:1px solid #e5e7eb;background:#f3f4f6;text-align:left\">股票</th>")
              .append("<th style=\"padding:8px 10px;border:1px solid #e5e7eb;background:#f3f4f6\">分组</th>")
              .append("<th style=\"padding:8px 10px;border:1px solid #e5e7eb;background:#f3f4f6\">最新成交量</th>")
              .append("<th style=\"padding:8px 10px;border:1px solid #e5e7eb;background:#f3f4f6\">量比</th>")
              .append("<th style=\"padding:8px 10px;border:1px solid #e5e7eb;background:#f3f4f6\">当日涨跌</th>")
              .append("<th style=\"padding:8px 10px;border:1px solid #e5e7eb;background:#f3f4f6\">方向</th>")
              .append("</tr>");
            for (var stock : volAnomalies) {
                var v = stock.volume();
                String chgColor = v.dayChangePct() >= 0 ? "#16a34a" : "#dc2626";
                String direction = v.dayChangePct() >= 0 ? "放量上涨" : "放量下跌";
                String dayChangeStr = String.format("%s%.2f%%", v.dayChangePct() >= 0 ? "+" : "", v.dayChangePct());
                String ratioStr = String.format("%.1f×", v.ratio());
                sb.append("<tr>")
                  .append("<td style=\"padding:8px 10px;border:1px solid #e5e7eb;font-weight:600\">")
                  .append(stock.stockName())
                  .append(" <span style=\"color:#8b949e;font-size:12px\">").append(stock.stockKey()).append("</span></td>")
                  .append("<td style=\"padding:8px 10px;border:1px solid #e5e7eb;text-align:center\">").append(stock.groupName()).append("</td>")
                  .append("<td style=\"padding:8px 10px;border:1px solid #e5e7eb;text-align:center\">").append(formatVolume(v.latestVol())).append("</td>")
                  .append("<td style=\"padding:8px 10px;border:1px solid #e5e7eb;text-align:center;color:#dc2626;font-weight:700\">").append(ratioStr).append("</td>")
                  .append("<td style=\"padding:8px 10px;border:1px solid #e5e7eb;text-align:center;color:").append(chgColor).append("\">").append(dayChangeStr).append("</td>")
                  .append("<td style=\"padding:8px 10px;border:1px solid #e5e7eb;font-size:12px\">").append(direction).append("</td>")
                  .append("</tr>");
            }
            sb.append("</table>");
        }

        return htmlWrap(sb.toString());
    }

    /**
     * 最近7日右侧趋势色块条（嵌套 table，邮件客户端兼容）：
     * 每格一个绿(进入)/红(未进入) td，下方标 MM-dd。
     * {@code errorTail}=true 时（失败/数据过旧股）末尾追加一格灰色 ⚠ —— 保留历史成功数据展示，
     * 仅"出问题的这一天"标记异常；历史为空则退化为单格 ⚠。
     */
    /** 最近交易日涨跌幅文本：+X.XX% / -X.XX%；失败股或无 volume 数据显示 "-"。 */
    private static String dayChangeText(com.autotrading.market.RightTrendAnalysisService.StockTrendResult stock) {
        if (!stock.success() || stock.volume() == null) return "-";
        double pct = stock.volume().dayChangePct();
        return String.format("%s%.2f%%", pct >= 0 ? "+" : "", pct);
    }

    /** 涨跌幅配色：涨绿跌红（与邮件既有涨跌配色一致）；失败股灰色。 */
    private static String dayChangeColor(com.autotrading.market.RightTrendAnalysisService.StockTrendResult stock) {
        if (!stock.success() || stock.volume() == null) return "#8b949e";
        return stock.volume().dayChangePct() >= 0 ? "#16a34a" : "#dc2626";
    }

    private static String trendStripHtml(
            java.util.List<com.autotrading.market.RightTrendAnalysisService.StockTrendResult.TrendDay> history,
            boolean currentInTrend, boolean errorTail) {
        boolean hasHistory = history != null && !history.isEmpty();
        if (!hasHistory && !errorTail) {
            return currentInTrend
                    ? "<span style=\"color:#16a34a;font-weight:700\">是</span>"
                    : "<span style=\"color:#dc2626\">否</span>";
        }
        StringBuilder sb = new StringBuilder("<table style=\"border-collapse:collapse;margin:0 auto\"><tr>");
        if (hasHistory) {
            for (com.autotrading.market.RightTrendAnalysisService.StockTrendResult.TrendDay d : history) {
                String date = d.date();
                String mmdd = (date != null && date.length() >= 10) ? date.substring(5) : (date == null ? "" : date);
                String bg = d.isInRightTrend() ? "#16a34a" : "#dc2626";
                sb.append("<td style=\"padding:3px 5px;border:1px solid #fff;text-align:center;background:")
                  .append(bg).append(";color:#fff;font-size:11px;line-height:1.15;min-width:34px\">")
                  .append(d.isInRightTrend() ? "✓" : "✗").append("<br>").append(mmdd).append("</td>");
            }
        }
        if (errorTail) {
            sb.append("<td style=\"padding:3px 5px;border:1px solid #fff;text-align:center;background:#8b949e;")
              .append("color:#fff;font-size:11px;line-height:1.15;min-width:34px\">⚠<br>异常</td>");
        }
        sb.append("</tr></table>");
        return sb.toString();
    }

    /** Colored pill for the LLM top/bottom signal + its evidence reason (below the pill). */
    private static String topBottomCell(String signal, String reason) {
        String pill;
        if (signal == null) {
            pill = "<span style=\"color:#8b949e\">-</span>";
        } else {
            pill = switch (signal) {
                case "near_top" -> "<span style=\"color:#dc2626;font-weight:600\">接近顶部</span>";
                case "near_bottom" -> "<span style=\"color:#16a34a;font-weight:600\">接近底部</span>";
                case "mid" -> "<span style=\"color:#8b949e\">中段</span>";
                default -> "<span style=\"color:#8b949e\">-</span>";
            };
        }
        StringBuilder cell = new StringBuilder(pill);
        if (reason != null && !reason.isBlank()) {
            cell.append("<div style=\"font-size:11px;color:#8b949e;margin-top:2px\">")
                .append(reason).append("</div>");
        }
        return cell.toString();
    }

    // ---- Trading Signal ----

    public static String signalSubject(com.autotrading.monitor.TradingSignalScanner.SignalRecord rec) {
        String action = "BUY".equals(rec.signalType()) ? "买入信号" : "卖出信号";
        return "[买卖点] " + rec.stockName() + "(" + rec.stockKey() + ") " + action + " - " + rec.strategy();
    }

    public static String signalBody(com.autotrading.monitor.TradingSignalScanner.SignalRecord rec) {
        boolean isBuy = "BUY".equals(rec.signalType());
        String color = isBuy ? GREEN : RED;
        String action = isBuy ? "买入信号" : "卖出信号";
        String[] parts = rec.stockKey().split("\\.");
        String marketLabel = "未知";
        try { marketLabel = marketLabel(parts.length > 0 ? Integer.parseInt(parts[0]) : 0); } catch (Exception e) {}

        StringBuilder sb = new StringBuilder();
        sb.append("<h2 style=\"color:").append(color).append("\">")
          .append(action).append(" - ").append(rec.strategy()).append("</h2>");
        sb.append("<table style=\"border-collapse:collapse;width:100%;font-size:14px\">");
        sb.append(row("股票", rec.stockName() + " (" + rec.stockKey() + ")"));
        sb.append(colorRow("信号类型", action, color));
        sb.append(row("策略", rec.strategy()));
        sb.append(row("当前价", formatPrice(rec.price())));
        sb.append(row("信号日期", rec.signalDate()));
        sb.append(row("分析原因", rec.reason()));
        sb.append(row("市场", marketLabel));
        sb.append(row("时间", TS_FMT.format(Instant.ofEpochMilli(rec.timestamp()))));
        sb.append("</table>");
        return htmlWrap(sb.toString());
    }
    private static String freqLabel(String frequency) {
        return "week".equalsIgnoreCase(frequency) ? "周线" : "日线";
    }

    private static String htmlWrap(String body) {
        return "<div style=\"font-family:Arial,sans-serif;max-width:600px;margin:0 auto\">" +
                body +
                "<hr style=\"border:none;border-top:1px solid #ddd;margin:20px 0\">" +
                "<p style=\"font-size:12px;color:#999\">Futu Stock Monitor 自动告警</p>" +
                "</div>";
    }

    private static String row(String label, String value) {
        return "<tr><td style=\"padding:8px 12px;border:1px solid #e5e7eb;background:#f9fafb;font-weight:bold;width:30%\">" + label + "</td>" +
                "<td style=\"padding:8px 12px;border:1px solid #e5e7eb\">" + value + "</td></tr>";
    }

    private static String colorRow(String label, String value, String color) {
        return "<tr><td style=\"padding:8px 12px;border:1px solid #e5e7eb;background:#f9fafb;font-weight:bold\">" + label + "</td>" +
                "<td style=\"padding:8px 12px;border:1px solid #e5e7eb;color:" + color + ";font-weight:bold\">" + value + "</td></tr>";
    }

    private static String formatPrice(double price) {
        return String.format("%.4f", price);
    }

    private static String formatVolume(long vol) {
        if (vol >= 100_000_000) return String.format("%.2f亿", vol / 100_000_000.0);
        if (vol >= 10_000) return String.format("%.2f万", vol / 10_000.0);
        return String.valueOf(vol);
    }

    private static String marketLabel(int market) {
        if (market == StockInfo.MARKET_US) return "美股";
        if (market == StockInfo.MARKET_HK) return "港股";
        if (market == StockInfo.MARKET_CN_SH) return "A股(沪)";
        if (market == StockInfo.MARKET_CN_SZ) return "A股(深)";
        return "M" + market;
    }
}
