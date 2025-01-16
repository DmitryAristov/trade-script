package org.tradebot.binance;

import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.tradebot.domain.MarketEntry;
import org.tradebot.listener.MarketDataCallback;
import org.tradebot.service.TaskManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TradeHandlerTest {

    @InjectMocks
    private TradeHandler tradeHandler;

    @Mock
    private MarketDataCallback callback;

    @Mock
    private TaskManager taskManager;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        tradeHandler = new TradeHandler(taskManager);
    }

    @Test
    void testSubscribeAndUnsubscribe() {
        tradeHandler.setCallback(callback);
        assertEquals(tradeHandler.callback, callback);
    }

    @Test
    void testOnMessage_AddsToQueue() {
        JSONObject message = new JSONObject().put("p", "100.5").put("q", "10.0");
        tradeHandler.onMessage(message);
        assertEquals(1, tradeHandler.activeQueue.size());
        assertEquals(message, tradeHandler.activeQueue.peekLast());
    }

    @Test
    void testUpdateMarketData_ProcessValidData() {
        JSONObject trade1 = new JSONObject().put("p", "100.5").put("q", "10.0");
        JSONObject trade2 = new JSONObject().put("p", "200.5").put("q", "20.0");
        tradeHandler.activeQueue.add(trade1);
        tradeHandler.activeQueue.add(trade2);
        doNothing().when(callback).notifyNewMarketEntry(anyLong(), any());
        tradeHandler.setCallback(callback);
        tradeHandler.updateMarketData();
        verify(callback).notifyNewMarketEntry(anyLong(), any(MarketEntry.class));
        assertTrue(tradeHandler.processingQueue.isEmpty());
    }

    @Test
    void testUpdateMarketData_EmptyQueue() {
        tradeHandler.lastPrice = 150.0;
        tradeHandler.activeQueue.clear();
        doNothing().when(callback).notifyNewMarketEntry(anyLong(), any());
        tradeHandler.setCallback(callback);
        tradeHandler.updateMarketData();
        verify(callback).notifyNewMarketEntry(anyLong(), any(MarketEntry.class));
    }

    @Test
    void testProcessEntry_ValidData() {
        MarketEntry entry = tradeHandler.processEntry(100.0, 200.0, 50.0);
        assertNotNull(entry);
        assertEquals(200.0, entry.high());
        assertEquals(100.0, entry.low());
        assertEquals(50.0, entry.volume());
    }

    @Test
    void testProcessEntry_EmptyData() {
        tradeHandler.lastPrice = 150.0;
        MarketEntry entry = tradeHandler.processEntry(Double.MAX_VALUE, Double.MIN_VALUE, 0.0);
        assertNotNull(entry);
        assertEquals(150.0, entry.high());
        assertEquals(150.0, entry.low());
        assertEquals(0.0, entry.volume());
    }
}
