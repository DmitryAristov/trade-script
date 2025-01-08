package org.tradebot.binance;

import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.tradebot.listener.UserDataCallback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class UserDataHandlerTest {

    @InjectMocks
    private UserDataHandler userDataHandler;

    @Mock
    private UserDataCallback callback;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testSetCallback() {
        userDataHandler.setCallback(callback);
        assertEquals(userDataHandler.callback, callback);
    }

    @Test
    void testOnMessage_OrderTradeUpdate() {
        JSONObject orderJson = new JSONObject()
                .put("X", "FILLED")
                .put("c", "testClientId");
        JSONObject message = new JSONObject().put("o", orderJson);
        doNothing().when(callback).notifyOrderUpdate("testClientId", "FILLED");
        userDataHandler.setCallback(callback);
        userDataHandler.onMessage("ORDER_TRADE_UPDATE", message);
        verify(callback).notifyOrderUpdate("testClientId", "FILLED");
    }

    @Test
    void testOnMessage_InvalidEventType() {
        JSONObject orderJson = new JSONObject()
                .put("X", "FILLED")
                .put("c", "testClientId");
        JSONObject message = new JSONObject().put("o", orderJson);
        userDataHandler.onMessage("INVALID_EVENT", message);
        verify(callback, never()).notifyOrderUpdate(anyString(), anyString());
    }
}
