package org.tradebot.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.tradebot.binance.RestAPIService;
import org.tradebot.binance.WebSocketService;
import org.tradebot.domain.Imbalance;
import org.tradebot.domain.Order;
import org.tradebot.domain.Position;
import org.tradebot.domain.Precision;

import java.util.Map;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class StrategyTest {

    private Strategy strategy;

    @Mock
    private RestAPIService apiService;
    @Mock
    private WebSocketService webSocketService;

    private final String symbol = "DOGEUSDT";
    private final int leverage = 10;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(apiService.getAccountBalance()).thenReturn(100.0);
        strategy = new Strategy(symbol, leverage, apiService, webSocketService);
        TradingBot.precision = new Precision(0, 2);
    }

    @Test
    void testInitialState() {
        assertEquals(Strategy.State.ENTRY_POINT_SEARCH, strategy.state);
    }

    @Test
    void testNotifyImbalanceStateUpdateProgress() {
        Imbalance imbalance = mock(Imbalance.class);
        strategy.notifyImbalanceStateUpdate(1000, ImbalanceService.State.PROGRESS, imbalance);

        verify(webSocketService).openUserDataStream();
        assertEquals(Strategy.State.ENTRY_POINT_SEARCH, strategy.state);
    }

    @Test
    void testNotifyImbalanceStateUpdatePotentialEndPoint() {
        Imbalance imbalance = mock(Imbalance.class);
        strategy.notifyImbalanceStateUpdate(1000, ImbalanceService.State.POTENTIAL_END_POINT, imbalance);

        verify(apiService, times(1)).placeOrder(any(Order.class));
    }

    @Test
    void testOpenPosition() {
        Imbalance imbalance = mock(Imbalance.class);
        when(imbalance.getType()).thenReturn(Imbalance.Type.UP);
        when(imbalance.getEndPrice()).thenReturn(0.3);

        strategy.notifyOrderBookUpdate(Map.of(0.3, 100.0), Map.of(0.29, 100.0));
        strategy.notifyImbalanceStateUpdate(1000, ImbalanceService.State.POTENTIAL_END_POINT, imbalance);

        verify(apiService).placeOrder(any(Order.class));
    }

    @Test
    void testCreateTakeAndStopOrders() {
        Imbalance imbalance = mock(Imbalance.class);
        when(imbalance.getType()).thenReturn(Imbalance.Type.UP);
        when(imbalance.getEndPrice()).thenReturn(0.3);
        when(imbalance.size()).thenReturn(0.01);

        strategy.notifyOrderBookUpdate(Map.of(0.3, 100.0), Map.of(0.29, 100.0));
        strategy.notifyImbalanceStateUpdate(1000, ImbalanceService.State.POTENTIAL_END_POINT, imbalance);

        verify(apiService, times(3)).placeOrder(any(Order.class));
    }

    @Test
    void testClosePositionByTimeout() {
        Position mockPosition = new Position();
        mockPosition.setEntryPrice(0.31);
        mockPosition.setPositionAmt(1);
        when(apiService.getOpenPosition(symbol)).thenReturn(mockPosition);

        strategy.closePositionByTimeout();

        verify(apiService).placeOrder(any(Order.class));
    }

    @Test
    void testBreakEvenStop() {
        Position mockPosition = new Position();
        mockPosition.setEntryPrice(0.31);
        mockPosition.setPositionAmt(1);
        when(apiService.getOpenPosition(symbol)).thenReturn(mockPosition);
        strategy.placeBreakEvenStop();

        verify(apiService).placeOrder(any(Order.class));
    }

    @Test
    void testStopClosePositionTimer() {
        strategy.stopClosePositionTimer();

        // Timer should be shut down
        assertNull(strategy.closePositionTimer);
    }
}
