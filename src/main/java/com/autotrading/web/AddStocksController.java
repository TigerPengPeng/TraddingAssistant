package com.autotrading.web;

import com.autotrading.account.MarketInference;
import com.autotrading.account.StockGroupService;
import com.autotrading.futu.AsyncRequestBridge;
import com.autotrading.config.RightTrendProperties;
import com.autotrading.market.VisionOcrClient;
import com.autotrading.model.StockInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

/**
 * Adds stocks to Futu user-security groups from an uploaded image.
 * All stocks are written to the account's first Custom group (typically "关注").
 * System groups (US/HK/CN/All) are rejected by Futu modifyUserSecurity.
 * Market is inferred for display only — it does not affect the target group.
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
    private final RightTrendProperties properties;

  public AddStocksController(VisionOcrClient ocrClient, MarketInference inference,
                               StockGroupService stockGroupService,
                               RightTrendProperties properties) {
        this.ocrClient = ocrClient;
        this.inference = inference;
        this.stockGroupService = stockGroupService;
        this.properties = properties;
    }

    /**
     * Resolves the writable target group for all added stocks.
     * Futu modifyUserSecurity rejects System groups (US/HK/CN/All/etc),
     * so we target the first Custom group — typically "关注".
     */
   private String resolveTargetGroup() throws AsyncRequestBridge.FutuRequestException {
       List<StockGroupService.GroupInfo> groups = stockGroupService.getGroups();
       String preferred = properties.getGroupAdd();
       for (StockGroupService.GroupInfo g : groups) {
           if (g.name().equals(preferred) && !g.isSystem()) {
               return g.name();
           }
       }
        for (StockGroupService.GroupInfo g : groups) {
            if (!g.isSystem()) {
                return g.name();
            }
        }
        throw new AsyncRequestBridge.FutuRequestException(
                "账户中没有可写入的自定义分组。请在富途客户端创建一个自定义分组后再试。");
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
       String targetGroup;
       try {
           targetGroup = resolveTargetGroup();
       } catch (Exception e) {
           return new OcrResponse(false, e.getMessage(), List.of());
       }
       for (String code : codes) {
           MarketInference.Result r = inference.infer(code);
           items.add(new OcrItem(code, r.market(), r.marketLabel(),
                   targetGroup, r.isValid(), r.reason()));
       }
        String msg = items.isEmpty() ? "未识别到股票代码" : "识别到 " + items.size() + " 个候选";
        return new OcrResponse(true, msg, items);
    }

   /**
    * Step 2: write the confirmed items to Futu, grouped by target group.
    * Lists all user-security groups with their type (Custom vs System).
    * Used by the frontend group picker; only Custom groups are writable.
    */
   @GetMapping("/api/add-stocks/groups")
   public GroupsResponse groups() {
       try {
           return new GroupsResponse(true, "ok",
                   stockGroupService.getGroups().stream()
                           .map(g -> new GroupItem(g.name(), g.type(), g.isSystem()))
                           .toList());
       } catch (Exception e) {
           log.warn("getGroups failed: {}", e.getMessage());
           return new GroupsResponse(false, e.getMessage(), List.of());
       }
   }

   public record GroupsResponse(boolean ok, String message, List<GroupItem> groups) {}
   public record GroupItem(String name, int type, boolean isSystem) {}

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
           valid.add(new AddItem(item.code(), market, null));
       }
       if (valid.isEmpty()) {
           return new AddResponse(false, "没有有效的可添加项", List.of());
       }

       // All stocks go to the single writable Custom group.
       String targetGroup;
       try {
           targetGroup = resolveTargetGroup();
       } catch (Exception e) {
           return new AddResponse(false, e.getMessage(), List.of());
       }
       List<StockInfo> stocks = new ArrayList<>();
       for (AddItem item : valid) {
           stocks.add(new StockInfo(item.market(), item.code(), item.code()));
       }

       List<AddResult> results = new ArrayList<>();
       try {
           stockGroupService.addStocksToGroup(targetGroup, stocks);
           for (StockInfo s : stocks) {
               results.add(new AddResult(s.getCode(), targetGroup, true, "已添加"));
           }
       } catch (Exception ex) {
           log.warn("addStocksToGroup failed for group [{}]: {}", targetGroup, ex.getMessage());
           for (StockInfo s : stocks) {
               results.add(new AddResult(s.getCode(), targetGroup, false, ex.getMessage()));
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
