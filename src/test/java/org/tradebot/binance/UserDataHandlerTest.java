package org.tradebot.binance;

import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.tradebot.listener.UserDataListener;
import org.tradebot.binance.UserDataHandler;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class UserDataHandlerTest {

    @InjectMocks
    private UserDataHandler userDataHandler;

    @Mock
    private UserDataListener listener;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testSubscribeAndUnsubscribe() {
        userDataHandler.subscribe(listener);
        assertTrue(userDataHandler.listeners.contains(listener));
        userDataHandler.unsubscribe(listener);
        assertFalse(userDataHandler.listeners.contains(listener));
    }

    @Test
    void testOnMessage_OrderTradeUpdate() {
        JSONObject orderJson = new JSONObject()
                .put("X", "FILLED")
                .put("c", "testClientId");
        JSONObject message = new JSONObject().put("o", orderJson);
        doNothing().when(listener).notifyOrderUpdate("testClientId", "FILLED");
        userDataHandler.subscribe(listener);
        userDataHandler.onMessage("ORDER_TRADE_UPDATE", message);
        verify(listener).notifyOrderUpdate("testClientId", "FILLED");
    }

    @Test
    void testOnMessage_InvalidEventType() {
        JSONObject orderJson = new JSONObject()
                .put("X", "FILLED")
                .put("c", "testClientId");
        JSONObject message = new JSONObject().put("o", orderJson);
        userDataHandler.onMessage("INVALID_EVENT", message);
        verify(listener, never()).notifyOrderUpdate(anyString(), anyString());
    }
}
