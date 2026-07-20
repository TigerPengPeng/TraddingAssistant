package com.autotrading.account;

import com.autotrading.config.FutuProperties;
import com.autotrading.futu.AsyncRequestBridge;
import com.autotrading.futu.FutuConnectionManager;
import com.autotrading.model.StockInfo;
import com.futu.openapi.FTAPI_Conn_Qot;
import com.futu.openapi.pb.Common;
import com.futu.openapi.pb.QotModifyUserSecurity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StockGroupServiceTest {

    private FutuConnectionManager connectionManager;
    private AsyncRequestBridge bridge;
    private FTAPI_Conn_Qot conn;
    private StockGroupService service;

    @BeforeEach
    void setUp() {
        connectionManager = mock(FutuConnectionManager.class);
        bridge = spy(new AsyncRequestBridge());
        bridge.setDefaultTimeoutMs(2000);
        conn = mock(FTAPI_Conn_Qot.class);
        FutuProperties props = new FutuProperties();
        service = new StockGroupService(connectionManager, bridge, props);
    }

    @Test
    void notConnectedThrows() {
        when(connectionManager.getConnQot()).thenReturn(null);
        AsyncRequestBridge.FutuRequestException ex = assertThrows(
                AsyncRequestBridge.FutuRequestException.class,
                () -> service.addStocksToGroup("US", List.of(new StockInfo(11, "AAPL", "Apple"))));
        assertTrue(ex.getMessage().contains("Not connected"));
    }

   @Test
    void emptyListIsNoOp() throws Exception {
       // Returns without ever touching the connection.
        service.addStocksToGroup("US", List.of());
        verifyNoInteractions(connectionManager);
    }

    @Test
    void successPathCompletes() throws Exception {
        when(connectionManager.getConnQot()).thenReturn(conn);
        // modifyUserSecurity returns a serial; we pre-seed the bridge with the success response.
        int serial = 7001;
        when(conn.modifyUserSecurity(any())).thenReturn(serial);
        QotModifyUserSecurity.Response ok = QotModifyUserSecurity.Response.newBuilder()
                .setRetType(Common.RetType.RetType_Succeed_VALUE)
                .setRetMsg("OK")
                .build();
        // Seed the response before the call so bridge.await() resolves from cache.
        bridge.complete(serial, ok);

        service.addStocksToGroup("US", List.of(new StockInfo(11, "AAPL", "Apple")));

        verify(conn).modifyUserSecurity(any());
    }

    @Test
    void failureResponseThrowsWithRetMsg() {
        when(connectionManager.getConnQot()).thenReturn(conn);
        int serial = 7002;
        when(conn.modifyUserSecurity(any())).thenReturn(serial);
        QotModifyUserSecurity.Response fail = QotModifyUserSecurity.Response.newBuilder()
                .setRetType(Common.RetType.RetType_Succeed_VALUE + 1) // not Succeed
                .setRetMsg("GROUP_NOT_FOUND")
                .build();
        bridge.complete(serial, fail);

        AsyncRequestBridge.FutuRequestException ex = assertThrows(
                AsyncRequestBridge.FutuRequestException.class,
                () -> service.addStocksToGroup("US", List.of(new StockInfo(11, "AAPL", "Apple"))));
        assertTrue(ex.getMessage().contains("GROUP_NOT_FOUND"));
        assertTrue(ex.getMessage().contains("ModifyUserSecurity(Add) failed"));
    }
}
