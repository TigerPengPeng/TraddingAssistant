package com.autotrading.web;

import com.autotrading.config.RightTrendProperties;
import com.autotrading.monitor.RightTrendScheduler;
import com.autotrading.market.RightTrendAnalysisService.RightTrendReport;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * API endpoints for right-trend analysis.
 */
@RestController
@RequestMapping("/api/right-trend")
public class RightTrendController {

    private final RightTrendScheduler scheduler;
    private final RightTrendProperties properties;

    public RightTrendController(RightTrendScheduler scheduler,
                                  RightTrendProperties properties) {
        this.scheduler = scheduler;
        this.properties = properties;
    }

    @PostMapping("/analyze")
    public Map<String, Object> analyze(
            @RequestParam(defaultValue = "all") String groups,
            @RequestParam(defaultValue = "false") boolean sendEmail) {
        List<String> groupNames = resolveGroups(groups);
        RightTrendReport report = scheduler.runAnalysis(groupNames, sendEmail);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("report", report);
        return result;
    }

    @GetMapping("/latest")
    public Map<String, Object> getLatest() {
        RightTrendReport report = scheduler.getLatest();
        Map<String, Object> result = new LinkedHashMap<>();
        if (report == null) {
            result.put("status", "no_report");
            result.put("message", "尚未生成右侧趋势分析报告");
        } else {
            result.put("status", "ok");
            result.put("report", report);
        }
        return result;
    }

    @GetMapping("/history")
    public Map<String, Object> getHistory() {
        List<RightTrendReport> history = scheduler.getHistory();
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (RightTrendReport r : history) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("date", r.date());
            s.put("groups", r.groupNames());
            s.put("totalStocks", r.stocks().size());
            s.put("inRightTrend", r.stocks().stream().filter(st -> st.isInRightTrend()).count());
            s.put("generatedAt", r.generatedAt());
            summaries.add(s);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", summaries.size());
        result.put("reports", summaries);
        return result;
    }

    @GetMapping("/{date}")
    public Map<String, Object> getByDate(@PathVariable String date) {
        RightTrendReport report = scheduler.getByDate(date);
        Map<String, Object> result = new LinkedHashMap<>();
        if (report == null) {
            result.put("status", "not_found");
            result.put("message", "未找到 " + date + " 的右侧趋势分析报告");
        } else {
            result.put("status", "ok");
            result.put("report", report);
        }
        return result;
    }

    /**
     * Maps aliases (us/hk/cn/all) to configured Futu group names.
     * Also accepts comma-separated actual group names as fallback.
     */
    private List<String> resolveGroups(String input) {
        String trimmed = input.trim().toLowerCase();
        return switch (trimmed) {
            case "all" -> List.of(properties.getGroupUs(), properties.getGroupHk(), properties.getGroupCn());
            case "us" -> List.of(properties.getGroupUs());
            case "hk" -> List.of(properties.getGroupHk());
            case "cn" -> List.of(properties.getGroupCn());
            case "hk_cn", "hk+cn", "hkcn" -> List.of(properties.getGroupHk(), properties.getGroupCn());
            case "watch" -> List.of(properties.getGroupWatch());
            default -> Arrays.asList(input.split(","));
        };
    }
}
