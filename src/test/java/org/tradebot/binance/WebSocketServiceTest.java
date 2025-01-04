package org.tradebot.binance;

import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.tradebot.util.TaskManager;

import java.util.concurrent.TimeUnit;

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

    @Mock
    private TaskManager taskManager;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        webSocketService = spy(new WebSocketService("BTCUSDT", tradeHandler, orderBookHandler, userDataHandler, apiService, taskManager));
        doNothing().when(webSocketService).close();
        doNothing().when(webSocketService).connect();
        doNothing().when(webSocketService).reconnect();
        doNothing().when(webSocketService).run();
        doNothing().when(webSocketService).send(anyString());
        doNothing().when(webSocketService).sendPing();
    }

    @Test
    void testOnOpen() {
        when(webSocketService.isOpen()).thenReturn(true);
        ServerHandshake handshake = mock(ServerHandshake.class);
        webSocketService.onOpen(handshake);

        verify(webSocketService, times(1)).send(eq("{\"method\": \"SUBSCRIBE\", \"params\": [\"btcusdt@trade\"], \"id\": 1}"));
        verify(webSocketService, times(1)).send(eq("{\"method\": \"SUBSCRIBE\", \"params\": [\"btcusdt@depth@100ms\"], \"id\": 2}"));
        assertTrue(webSocketService.isReady());
    }

    @Test
    void testOnMessage_TradeEvent() {
        JSONObject message = new JSONObject().put("e", "trade");
        webSocketService.onMessage(message.toString());
        verify(tradeHandler).onMessage(
                argThat(argument -> argument.getString("e").equals("trade"))
        );
    }

    @Test
    void testOnMessage_DepthUpdateEvent() {
        JSONObject message = new JSONObject().put("e", "depthUpdate");
        webSocketService.onMessage(message.toString());
        verify(orderBookHandler).onMessage(
                argThat(argument -> argument.getString("e").equals("depthUpdate"))
        );
    }

    @Test
    void testOnMessage_UserDataEvent() {
        JSONObject message = new JSONObject().put("e", "ORDER_TRADE_UPDATE");

        webSocketService.onMessage(message.toString());

        verify(userDataHandler).onMessage(
                eq("ORDER_TRADE_UPDATE"),
                argThat(argument -> argument.getString("e").equals("ORDER_TRADE_UPDATE")));
    }

    @Test
    void testOnMessage_otherEvent() {
        JSONObject message = new JSONObject();
        message.put("e", "someEvent");
        webSocketService.onMessage(message.toString());
        verify(userDataHandler).onMessage(
                eq("someEvent"),
                argThat(jsonObject -> jsonObject.getString("e").equals("someEvent")));
    }

    @Test
    void testOnClose() {
        webSocketService.onClose(1000, "Normal closure", true);

        assertFalse(webSocketService.isReady());
    }

    @Test
    void testReconnectWebSocket_withUserStream() {
        when(webSocketService.isReady()).thenReturn(true);
        when(apiService.getUserStreamKey()).thenReturn("mockListenKey");
        webSocketService.userDataStream.set(true);

        webSocketService.reconnectWebSocket();

        verify(webSocketService, times(1)).send(eq("{\"method\": \"UNSUBSCRIBE\", \"params\": [\"btcusdt@trade\"], \"id\": 1}"));
        verify(webSocketService, times(1)).send(eq("{\"method\": \"UNSUBSCRIBE\", \"params\": [\"btcusdt@depth@100ms\"], \"id\": 2}"));
        verify(webSocketService, times(1)).send(eq("{\"method\": \"UNSUBSCRIBE\", \"params\": [\"\"], \"id\": 3}"));
        verify(apiService, times(1)).removeUserStreamKey();
        verify(apiService, times(1)).getUserStreamKey();
        verify(taskManager, times(1)).stop("user_data_stream_ping");
    }

    @Test
    void testReconnectWebSocket_withoutUserStream() {
        when(webSocketService.isReady()).thenReturn(true);
        when(apiService.getUserStreamKey()).thenReturn("mockListenKey");
        webSocketService.userDataStream.set(false);

        webSocketService.reconnectWebSocket();

        verify(webSocketService, times(1)).send(eq("{\"method\": \"UNSUBSCRIBE\", \"params\": [\"btcusdt@trade\"], \"id\": 1}"));
        verify(webSocketService, times(1)).send(eq("{\"method\": \"UNSUBSCRIBE\", \"params\": [\"btcusdt@depth@100ms\"], \"id\": 2}"));
        verify(webSocketService, times(0)).send(eq("{\"method\": \"UNSUBSCRIBE\", \"params\": [\"listenKey\"], \"id\": 3}"));
        verify(apiService, never()).removeUserStreamKey();
        verify(apiService, never()).getUserStreamKey();
        verify(taskManager, never()).stop("user_data_stream_ping");
    }

    @Test
    void testStopService() {
        webSocketService.userDataStream.set(true);

        webSocketService.stop();

        verify(webSocketService, times(1)).send(eq("{\"method\": \"UNSUBSCRIBE\", \"params\": [\"btcusdt@trade\"], \"id\": 1}"));
        verify(webSocketService, times(1)).send(eq("{\"method\": \"UNSUBSCRIBE\", \"params\": [\"btcusdt@depth@100ms\"], \"id\": 2}"));
        verify(webSocketService, times(1)).send(eq("{\"method\": \"UNSUBSCRIBE\", \"params\": [\"\"], \"id\": 3}"));
        verify(taskManager, times(1)).stop("user_data_stream_ping");
        verify(apiService, times(1)).removeUserStreamKey();
        verify(webSocketService, times(1)).close();
        assertFalse(webSocketService.isReady());
        assertFalse(webSocketService.userDataStream.get());
    }

    @Test
    void testUpdateUserDataStream_enable() {
        when(webSocketService.isReady()).thenReturn(true);
        when(apiService.getUserStreamKey()).thenReturn("mockListenKey");
        webSocketService.userDataStream.set(false);

        webSocketService.updateUserDataStream(true);

        verify(apiService, times(1)).getUserStreamKey();
        verify(taskManager, times(1)).create(eq("user_data_stream_ping"), any(), any(), eq(59L), eq(59L), eq(TimeUnit.MINUTES));
        verify(webSocketService, times(1)).send(eq("{\"method\": \"SUBSCRIBE\", \"params\": [\"mockListenKey\"], \"id\": 3}"));
        assertTrue(webSocketService.userDataStream.get());
    }

    @Test
    void testUpdateUserDataStream_enableWhenWSIsNotReady() {
        when(webSocketService.isReady()).thenReturn(false);
        webSocketService.userDataStream.set(false);

        webSocketService.updateUserDataStream(true);

        verify(apiService, times(0)).getUserStreamKey();
        verify(taskManager, times(0)).create(eq("user_data_stream_ping"), any(), any(), anyLong(), anyLong(), any(TimeUnit.class));
        verify(webSocketService, times(0)).send(anyString());
        assertTrue(webSocketService.userDataStream.get());
    }

    @Test
    void testUpdateUserDataStream_disable() {
        when(webSocketService.isReady()).thenReturn(true);
        webSocketService.userDataStream.set(true);

        webSocketService.updateUserDataStream(false);

        verify(apiService, times(1)).removeUserStreamKey();
        verify(taskManager, times(1)).stop("user_data_stream_ping");
        assertFalse(webSocketService.userDataStream.get());
    }

    @Test
    void testUpdateUserDataStream_disableWhenWsIsNotReady() {
        when(webSocketService.isReady()).thenReturn(false);
        webSocketService.userDataStream.set(true);

        webSocketService.updateUserDataStream(false);

        verify(apiService, times(0)).removeUserStreamKey();
        verify(taskManager, times(0)).stop(anyString());
        assertFalse(webSocketService.userDataStream.get());
    }
}
