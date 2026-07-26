package com.autotrading.web;

import com.autotrading.account.MarketInference;
import com.autotrading.account.StockGroupService;
import com.autotrading.config.RightTrendProperties;
import com.autotrading.futu.AsyncRequestBridge;
import com.autotrading.market.VisionOcrClient;
import com.autotrading.web.AddStocksController.AddItem;
import com.autotrading.web.AddStocksController.AddRequest;
import com.autotrading.web.AddStocksController.OcrResponse;
import org.junit.jupiter.api.BeforeEach;
import java.io.IOException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AddStocksControllerTest {

    private VisionOcrClient ocrClient;
    private StockGroupService stockGroupService;
    private MarketInference inference;
    private AddStocksController controller;

    @BeforeEach
    void setUp() throws Exception {
        ocrClient = mock(VisionOcrClient.class);
        stockGroupService = mock(StockGroupService.class);
        when(stockGroupService.getGroups()).thenReturn(List.of(
                new StockGroupService.GroupInfo("pool", 1, false)));
        RightTrendProperties props = new RightTrendProperties();
        props.setGroupUs("US");
        props.setGroupHk("HK");
        props.setGroupCn("CN");
        inference = new MarketInference(props);
       controller = new AddStocksController(ocrClient, inference, stockGroupService, props);
   }

    @Test
    void ocrReturnsPreviewWithInferredMarkets() throws Exception {
        when(ocrClient.isAvailable()).thenReturn(true);
        when(ocrClient.recognize(any(), anyString()))
                .thenReturn(List.of("AAPL", "00700", "600519", "555555"));

        OcrResponse resp = controller.ocr(stubFile());

        assertTrue(resp.ok());
        assertEquals(4, resp.items().size());
        // valid US / HK / SH, invalid unknown A-share prefix
        assertEquals("AAPL", resp.items().get(0).code());
        assertTrue(resp.items().get(0).valid());
        assertEquals("港股", resp.items().get(1).marketLabel());
        assertEquals("沪市", resp.items().get(2).marketLabel());
        assertFalse(resp.items().get(3).valid());
        // all items target the single writable custom group
        assertTrue(resp.items().stream().allMatch(i -> "pool".equals(i.targetGroup())));
    }

    @Test
    void ocrUnavailableReportsMessage() throws Exception {
        when(ocrClient.isAvailable()).thenReturn(false);
        OcrResponse resp = controller.ocr(stubFile());
        assertFalse(resp.ok());
        assertTrue(resp.message().contains("AI_VISION_API_KEY"));
        verifyNoInteractions(stockGroupService);
    }

    @Test
    void ocrRecognizeThrowsReportsMessage() throws Exception {
        when(ocrClient.isAvailable()).thenReturn(true);
        when(ocrClient.recognize(any(), anyString())).thenThrow(new RuntimeException("503"));
        OcrResponse resp = controller.ocr(stubFile());
        assertFalse(resp.ok());
        assertTrue(resp.message().contains("识别失败"));
    }

    @Test
    void addsAllValidStocksToSingleCustomGroup() throws Exception {
        // All valid stocks (US + HK + CN) go to the single writable custom group.
        AddRequest req = new AddRequest(List.of(
                new AddItem("AAPL", null, null),
                new AddItem("MSFT", null, null),
                new AddItem("00700", null, null),
                new AddItem("600519", null, null)));

        var resp = controller.add(req);

        assertTrue(resp.ok());
        assertEquals(4, resp.results().size());
        assertTrue(resp.results().stream().allMatch(r -> r.ok()));
        // single writable group -> one batched call with all 4 stocks
        verify(stockGroupService, times(1)).addStocksToGroup(eq("pool"), argThat(l -> l.size() == 4));
    }

    @Test
    void addReportsAllItemsFailedWhenFutuRejectsGroup() throws Exception {
        doThrow(new AsyncRequestBridge.FutuRequestException("GROUP_NOT_FOUND"))
                .when(stockGroupService).addStocksToGroup(eq("pool"), anyList());

        AddRequest req = new AddRequest(List.of(
                new AddItem("AAPL", null, null),
                new AddItem("00700", null, null)));

        var resp = controller.add(req);

        // Single custom group rejected -> all items fail, ok=false.
        assertFalse(resp.ok());
        assertEquals(2, resp.results().size());
        assertTrue(resp.results().stream().noneMatch(r -> r.ok()));
        assertTrue(resp.results().stream().allMatch(r -> r.message().contains("GROUP_NOT_FOUND")));
    }

    @Test
    void addDropsInvalidItemsBeforeCallingFutu() throws Exception {
        AddRequest req = new AddRequest(List.of(
                new AddItem("AAPL", null, null),
                new AddItem("555555", null, null))); // invalid 6-digit

        var resp = controller.add(req);

        assertTrue(resp.ok());
        assertEquals(1, resp.results().size());
        verify(stockGroupService).addStocksToGroup(eq("pool"), argThat(l -> l.size() == 1));
    }

    @Test
    void addEmptyRequestReportsNothing() {
        var resp = controller.add(new AddRequest(List.of()));
        assertFalse(resp.ok());
        verifyNoInteractions(stockGroupService);
    }

    /** Minimal MultipartFile stub: only getBytes/getContentType/isEmpty are used. */
   private static org.springframework.web.multipart.MultipartFile stubFile() throws java.io.IOException {
        org.springframework.web.multipart.MultipartFile f =
                mock(org.springframework.web.multipart.MultipartFile.class);
        try {
            when(f.getBytes()).thenReturn(new byte[]{1, 2, 3});
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        when(f.getContentType()).thenReturn("image/png");
        when(f.isEmpty()).thenReturn(false);
        return f;
    }
}
