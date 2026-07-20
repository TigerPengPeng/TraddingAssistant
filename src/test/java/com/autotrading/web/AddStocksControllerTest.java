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
    void setUp() {
        ocrClient = mock(VisionOcrClient.class);
        stockGroupService = mock(StockGroupService.class);
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
        assertEquals("HK", resp.items().get(1).targetGroup());
        assertEquals("CN", resp.items().get(2).targetGroup());
        assertFalse(resp.items().get(3).valid());
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
    void addGroupsByTargetGroupAndBatchesPerGroup() throws Exception {
        // US + HK + CN -> three groups, three modifyUserSecurity calls.
        AddRequest req = new AddRequest(List.of(
                new AddItem("AAPL", null, null),
                new AddItem("MSFT", null, null),
                new AddItem("00700", null, null),
                new AddItem("600519", null, null)));

        var resp = controller.add(req);

        assertTrue(resp.ok());
        assertEquals(4, resp.results().size());
        assertTrue(resp.results().stream().allMatch(r -> r.ok()));
        // three distinct groups -> three batched calls
        verify(stockGroupService, times(3)).addStocksToGroup(anyString(), anyList());
        verify(stockGroupService).addStocksToGroup(eq("US"), argThat(l -> l.size() == 2));
        verify(stockGroupService).addStocksToGroup(eq("HK"), argThat(l -> l.size() == 1));
        verify(stockGroupService).addStocksToGroup(eq("CN"), argThat(l -> l.size() == 1));
    }

    @Test
    void addReportsPerItemFailureWhenFutuRejectsGroup() throws Exception {
        doThrow(new AsyncRequestBridge.FutuRequestException("GROUP_NOT_FOUND"))
                .when(stockGroupService).addStocksToGroup(eq("HK"), anyList());

        AddRequest req = new AddRequest(List.of(
                new AddItem("AAPL", null, null),
                new AddItem("00700", null, null)));

        var resp = controller.add(req);

        // US succeeded, HK failed; overall ok=true because at least one group worked.
        assertTrue(resp.ok());
        var hk = resp.results().stream().filter(r -> r.targetGroup().equals("HK")).findFirst().orElseThrow();
        assertFalse(hk.ok());
        assertTrue(hk.message().contains("GROUP_NOT_FOUND"));
    }

    @Test
    void addDropsInvalidItemsBeforeCallingFutu() throws Exception {
        AddRequest req = new AddRequest(List.of(
                new AddItem("AAPL", null, null),
                new AddItem("555555", null, null))); // invalid 6-digit

        var resp = controller.add(req);

        assertTrue(resp.ok());
        assertEquals(1, resp.results().size());
        verify(stockGroupService).addStocksToGroup(eq("US"), argThat(l -> l.size() == 1));
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
