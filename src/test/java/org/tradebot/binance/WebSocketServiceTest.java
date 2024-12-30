package org.tradebot.binance;

import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class WebSocketServiceTest {

    @InjectMocks
    private WebSocketService webSocketService;

    @Mock
    private TradeHandler tradeHandler;

    @Mock
    private OrderBookHandler orderBookHandler;

    @Mock
    private UserDataHandler userDataHandler;

    @Mock
    private RestAPIService apiService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        webSocketService = spy(new WebSocketService("BTCUSDT", tradeHandler, orderBookHandler, userDataHandler, apiService));
    }

    @Test
    void testOnOpen() {
        ServerHandshake handshake = mock(ServerHandshake.class);
        doNothing().when(webSocketService).send(anyString());
        webSocketService.onOpen(handshake);
        verify(webSocketService, times(2)).send(anyString());
    }

    @Test
    void testOnMessage_TradeEvent() {
        JSONObject message = new JSONObject().put("e", "trade");
        webSocketService.onMessage(message.toString());
        verify(tradeHandler).onMessage(any(JSONObject.class));
    }

    @Test
    void testOnMessage_DepthUpdateEvent() {
        JSONObject message = new JSONObject().put("e", "depthUpdate");
        webSocketService.onMessage(message.toString());
        verify(orderBookHandler).onMessage(any(JSONObject.class));
    }

    @Test
    void testOnMessage_UserDataEvent() {
        JSONObject message = new JSONObject().put("e", "ORDER_TRADE_UPDATE");
        webSocketService.onMessage(message.toString());
        verify(userDataHandler).onMessage(anyString(), any(JSONObject.class));
    }

    @Test
    void testOnClose() {
        webSocketService.onClose(1000, "Normal closure", true);
        assertTrue(true);
    }

    @Test
    void testUnsubscribe() {
        doNothing().when(tradeHandler).stop();
        doNothing().when(apiService).removeUserStreamKey();
        doNothing().when(webSocketService).send(anyString());
        webSocketService.unsubscribe();
        verify(webSocketService, times(2)).send(anyString());
        verify(tradeHandler).stop();
    }

    @Test
    void testOpenUserDataStream() {
        when(apiService.getUserStreamKey()).thenReturn("testListenKey");
        doNothing().when(apiService).keepAliveUserStreamKey();
        doNothing().when(webSocketService).send(anyString());
        webSocketService.openUserDataStream();
        verify(webSocketService).send(anyString());
        verify(apiService).getUserStreamKey();
    }

    @Test
    void testCloseUserDataStream() {
        when(apiService.getUserStreamKey()).thenReturn("testListenKey");
        doNothing().when(apiService).removeUserStreamKey();
        doNothing().when(webSocketService).send(anyString());
        webSocketService.openUserDataStream();
        webSocketService.closeUserDataStream();
        verify(apiService).removeUserStreamKey();
    }
}
