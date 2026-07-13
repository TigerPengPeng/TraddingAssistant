package com.autotrading.web;

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

    public RightTrendController(RightTrendScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @PostMapping("/analyze")
    public Map<String, Object> analyze(
            @RequestParam(defaultValue = "美股,港股,沪深") String groups,
            @RequestParam(defaultValue = "false") boolean sendEmail) {
        List<String> groupList = Arrays.asList(groups.split(","));
        RightTrendReport report = scheduler.runAnalysis(groupList, sendEmail);
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
}
