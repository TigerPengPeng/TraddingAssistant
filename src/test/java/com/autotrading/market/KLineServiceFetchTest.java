package com.autotrading.market;

import com.autotrading.futu.AsyncRequestBridge;
import com.autotrading.futu.FutuConnectionManager;
import com.autotrading.model.StockInfo;
import com.futu.openapi.FTAPI_Conn_Qot;
import com.futu.openapi.pb.Common;
import com.futu.openapi.pb.QotCommon.KLine;
import com.futu.openapi.pb.QotCommon.Security;
import com.futu.openapi.pb.QotRequestHistoryKL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for KLineService.fetchKLines request/response handling against the
 * Futu OpenD history-KL contract: OpenD returns the OLDEST maxAckKLNum bars
 * from beginTime when the window holds more, so the request cap must exceed
 * the window's bar count and the client must keep the most recent 120.
 */
class KLineServiceFetchTest {

    private FutuConnectionManager connMgr;
    private AsyncRequestBridge bridge;
    private FTAPI_Conn_Qot conn;
    private KLineService service;

    @BeforeEach
    void setUp() {
        connMgr = mock(FutuConnectionManager.class);
        bridge = mock(AsyncRequestBridge.class);
        conn = mock(FTAPI_Conn_Qot.class);
        when(connMgr.getConnQot()).thenReturn(conn);
        service = new KLineService(connMgr, bridge, 360);
    }

    private static List<KLine> buildBars(int count) {
        List<KLine> bars = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 2, 20);
        for (int i = 0; i < count; i++) {
            bars.add(KLine.newBuilder()
                    .setTime(d.plusDays(i) + " 00:00:00")
                    .setOpenPrice(100 + i).setHighPrice(101 + i)
                    .setLowPrice(99 + i).setClosePrice(100.5 + i)
                    .setVolume(1000L + i).setChangeRate(0.1)
                    .setIsBlank(false)
                    .build());
        }
        return bars;
    }

    private void stubResponse(List<KLine> bars) throws Exception {
        QotRequestHistoryKL.Response resp = QotRequestHistoryKL.Response.newBuilder()
                .setRetType(Common.RetType.RetType_Succeed_VALUE)
                .setS2C(QotRequestHistoryKL.S2C.newBuilder()
                        .setSecurity(Security.newBuilder().setMarket(11).setCode("MSFT"))
                        .addAllKlList(bars))
                .build();
        when(conn.requestHistoryKL(any())).thenReturn(7);
        when(bridge.await(eq(7), eq(QotRequestHistoryKL.Response.class))).thenReturn(resp);
    }

    @Test
    @DisplayName("窗口内 bar 数 > 120 时保留最新 120 根（OpenD 返回最旧 N 根，不能截掉最新 bar）")
    void keepsMostRecent120WhenWindowHasMore() throws Exception {
        // 模拟 2026-08-14 美股实测场景：175 天窗口内 123 根日 K，
        // OpenD 按 maxAckKLNum 从 beginTime 返回最旧的一页
        stubResponse(buildBars(123));

        List<KLineService.KLineData> result = service.fetchKLines(new StockInfo(11, "MSFT", "MSFT"));

        assertEquals(120, result.size(), "客户端只保留最新 120 根");
        List<KLine> all = buildBars(123);
        assertEquals(all.get(122).getTime(), result.get(119).time(), "最后一根必须是窗口内最新 bar");
        assertEquals(all.get(3).getTime(), result.get(0).time(), "最旧的 3 根被客户端丢弃");
    }

    @Test
    @DisplayName("请求上限 maxAckKLNum 必须大于窗口 bar 数（=1000），否则 OpenD 截掉最新 bar")
    void requestCapExceedsWindowBarCount() throws Exception {
        stubResponse(buildBars(5));

        service.fetchKLines(new StockInfo(11, "MSFT", "MSFT"));

        ArgumentCaptor<QotRequestHistoryKL.Request> captor =
                ArgumentCaptor.forClass(QotRequestHistoryKL.Request.class);
        verify(conn).requestHistoryKL(captor.capture());
        assertTrue(captor.getValue().getC2S().getMaxAckKLNum() > 130,
                "maxAckKLNum 需 > 900 天窗口的周 K 数（~130），实际: "
                        + captor.getValue().getC2S().getMaxAckKLNum());
    }

    @Test
    @DisplayName("窗口内 bar 数 ≤ 120 时原样返回；空白 bar 跳过")
    void returnsAllWhenUnderCap() throws Exception {
        List<KLine> bars = buildBars(5);
        bars.add(KLine.newBuilder().setTime("2026-02-25 00:00:00").setIsBlank(true).build());
        stubResponse(bars);

        List<KLineService.KLineData> result = service.fetchKLines(new StockInfo(1, "00700", "腾讯"));

        assertEquals(5, result.size(), "空白 bar 不计入");
        assertEquals(buildBars(5).get(4).getTime(), result.get(4).time());
    }
}
