package com.autotrading.web;

import com.autotrading.account.MarketInference;
import com.autotrading.account.StockGroupService;
import com.autotrading.market.VisionOcrClient;
import com.autotrading.model.StockInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.*;

/**
 * Adds stocks to Futu user-security groups from an uploaded image.
 * <p>
 * Two-step flow: OCR returns a preview the user can edit, then the user
 * confirms the items they want to actually write to Futu.
 */
@RestController
public class AddStocksController {

    private static final Logger log = LoggerFactory.getLogger(AddStocksController.class);

    private final VisionOcrClient ocrClient;
    private final MarketInference inference;
    private final StockGroupService stockGroupService;

    public AddStocksController(VisionOcrClient ocrClient, MarketInference inference,
                               StockGroupService stockGroupService) {
        this.ocrClient = ocrClient;
        this.inference = inference;
        this.stockGroupService = stockGroupService;
    }

    /**
     * Step 1: recognize codes in an uploaded image and return a preview
     * (code + inferred market/group/validity) for user confirmation.
     */
    @PostMapping("/api/add-stocks/ocr")
    public OcrResponse ocr(@RequestParam("file") MultipartFile file) throws IOException {
        if (!ocrClient.isAvailable()) {
            return new OcrResponse(false, "未配置视觉模型 (AI_VISION_API_KEY 为空)，无法识别图片", List.of());
        }
        if (file == null || file.isEmpty()) {
            return new OcrResponse(false, "未上传图片", List.of());
        }

        List<String> codes;
        try {
            codes = ocrClient.recognize(file.getBytes(), file.getContentType());
        } catch (Exception e) {
            log.warn("OCR failed: {}", e.getMessage());
            return new OcrResponse(false, "识别失败: " + e.getMessage(), List.of());
        }

        List<OcrItem> items = new ArrayList<>();
        for (String code : codes) {
            MarketInference.Result r = inference.infer(code);
            items.add(new OcrItem(code, r.market(), r.marketLabel(),
                    r.targetGroup(), r.isValid(), r.reason()));
        }
        String msg = items.isEmpty() ? "未识别到股票代码" : "识别到 " + items.size() + " 个候选";
        return new OcrResponse(true, msg, items);
    }

    /**
     * Step 2: write the confirmed items to Futu, grouped by target group.
     * Each item is reported individually (ok/failed/already), so the user
     * sees exactly what happened and can retry failures.
     */
    @PostMapping("/api/add-stocks")
    public AddResponse add(@RequestBody AddRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            return new AddResponse(false, "无可添加的股票", List.of());
        }

        // Keep only valid items; the frontend should already filter, but we
        // defend here too so an invalid item never reaches Futu.
        List<AddItem> valid = new ArrayList<>();
        for (AddItem item : request.items()) {
            MarketInference.Result r = inference.infer(item.code());
            if (!r.isValid()) {
                continue;
            }
            // Honor the user's edited market if it parses, else re-infer.
            int market = item.market() != null && item.market() > 0 ? item.market() : r.market();
            String group = (item.targetGroup() == null || item.targetGroup().isBlank())
                    ? r.targetGroup() : item.targetGroup();
            valid.add(new AddItem(item.code(), market, group));
        }
        if (valid.isEmpty()) {
            return new AddResponse(false, "没有有效的可添加项", List.of());
        }

        // Group by target group so each group is a single modifyUserSecurity call.
        Map<String, List<StockInfo>> byGroup = new LinkedHashMap<>();
        for (AddItem item : valid) {
            byGroup.computeIfAbsent(item.targetGroup(), k -> new ArrayList<>())
                    .add(new StockInfo(item.market(), item.code(), item.code()));
        }

        List<AddResult> results = new ArrayList<>();
        for (Map.Entry<String, List<StockInfo>> e : byGroup.entrySet()) {
            String group = e.getKey();
            List<StockInfo> stocks = e.getValue();
            try {
                stockGroupService.addStocksToGroup(group, stocks);
                for (StockInfo s : stocks) {
                    results.add(new AddResult(s.getCode(), group, true, "已添加"));
                }
            } catch (Exception ex) {
                log.warn("addStocksToGroup failed for group [{}]: {}", group, ex.getMessage());
                for (StockInfo s : stocks) {
                    results.add(new AddResult(s.getCode(), group, false, ex.getMessage()));
                }
            }
        }
        long ok = results.stream().filter(AddResult::ok).count();
        String msg = ok + "/" + results.size() + " 成功";
        return new AddResponse(ok > 0, msg, results);
    }

    public record OcrResponse(boolean ok, String message, List<OcrItem> items) {}
    public record OcrItem(String code, Integer market, String marketLabel,
                          String targetGroup, boolean valid, String reason) {}
    public record AddRequest(List<AddItem> items) {}
    public record AddItem(String code, Integer market, String targetGroup) {}
    public record AddResponse(boolean ok, String message, List<AddResult> results) {}
    public record AddResult(String code, String targetGroup, boolean ok, String message) {}
}
