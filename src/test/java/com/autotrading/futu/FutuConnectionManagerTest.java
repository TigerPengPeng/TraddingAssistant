package com.autotrading.futu;

import com.autotrading.config.FutuProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Regression test for the post-reconnect hook (review finding C1).
 *
 * onConnected() previously detected reconnects via {@code connTrd != null}, but
 * connTrd is never instantiated, so postReconnectHook never fired and the system
 * silently lost subscriptions after any reconnect. This test pins the fix: the
 * hook must fire on the second onConnected() (reconnect) but not the first.
 *
 * onConnected() touches only latch/counter/flag state — no SDK calls — so it
 * can be unit-tested without OpenD.
 */
class FutuConnectionManagerTest {

    @Test
    @DisplayName("postReconnectHook 仅在重连（非首次连接）时触发")
    void postReconnectHookFiresOnlyAfterReconnect() {
        FutuConnectionManager mgr = new FutuConnectionManager(
                new FutuProperties(), new AsyncRequestBridge(), mock(FutuQuoteHandler.class));

        AtomicBoolean fired = new AtomicBoolean(false);
        mgr.setPostReconnectHook(() -> fired.set(true));

        mgr.onConnected();   // 首次连接
        assertFalse(fired.get(), "首次连接不应触发 postReconnectHook");

        mgr.onConnected();   // 重连
        assertTrue(fired.get(), "重连必须触发 postReconnectHook");
    }
}
