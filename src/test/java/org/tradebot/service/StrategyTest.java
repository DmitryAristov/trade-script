package org.tradebot.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.tradebot.binance.APIService;
import org.tradebot.domain.*;
import org.tradebot.service.strategy_state_handlers.StrategyStateDispatcher;


import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;

//TODO
class StrategyTest {
    private final String symbol = "DOGEUSDT";
    private final int leverage = 10;

    private Strategy strategy;

    @Mock
    private APIService apiService;
    @Mock
    private TaskManager taskManager;
    @Mock
    private OrderManager orderManager;
    @Mock
    private StrategyStateDispatcher stateDispatcher;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(apiService.getAccountBalance()).thenReturn(HTTPResponse.success(200, 100.0));
        strategy = new Strategy(apiService, taskManager, symbol, leverage);
        strategy.setOrderManager(orderManager);
        strategy.setStateDispatcher(stateDispatcher);
    }

    @Test
    @Disabled
    void testNotifyImbalanceStateUpdateWithWebSocketReady() {
        // Mock WebSocket state
        when(orderManager.getLock()).thenReturn(new Object());
        strategy.notifyWebsocketStateChanged(true);

        Imbalance imbalance = mock(Imbalance.class);
        when(imbalance.getType()).thenReturn(Imbalance.Type.UP);
        strategy.notifyOrderBookUpdate(Map.of(0.3, 100.0), Map.of(0.29, 100.0));

        strategy.notifyImbalanceStateUpdate(1000L, ImbalanceService.State.POTENTIAL_END_POINT, imbalance);

        verify(orderManager).placeOpenOrder(eq(imbalance), eq(0.3));
    }

    @Test
    void testNotifyImbalanceStateUpdateWithWebSocketNotReady() {
        strategy.notifyWebsocketStateChanged(false);

        Imbalance imbalance = mock(Imbalance.class);

        strategy.notifyImbalanceStateUpdate(1000L, ImbalanceService.State.POTENTIAL_END_POINT, imbalance);
    }

    @Test
    @Disabled
    void testNotifyWebSocketStateChangedToReady() {
        when(orderManager.getLock()).thenReturn(new Object());
        strategy.notifyWebsocketStateChanged(false);

        strategy.notifyWebsocketStateChanged(true);

        verify(taskManager).cancel(eq(Strategy.CHECK_ORDERS_API_MODE_TASK_KEY));
    }

    @Test
    void testNotifyWebSocketStateChangedToNotReady() {
        strategy.webSocketReady.set(true);
        strategy.notifyWebsocketStateChanged(false);

//        verify(taskManager).scheduleAtFixedRate(
//                eq(Strategy.CHECK_ORDERS_API_MODE_TASK_KEY),
//                any(Runnable.class),
//                eq(0L),
//                eq(1L),
//                eq(TimeUnit.SECONDS)
//        );
    }

    @Test
    void testCheckOrdersAPI() {
        Position mockPosition = mock(Position.class);
        List<Order> mockOrders = List.of(mock(Order.class));
        when(orderManager.getLock()).thenReturn(new Object());

        when(apiService.getOpenPosition(symbol)).thenReturn(HTTPResponse.success(200, mockPosition));
        when(apiService.getOpenOrders(symbol)).thenReturn(HTTPResponse.success(200, mockOrders));

        strategy.checkOrdersAPI();

        verify(stateDispatcher).dispatch(eq(mockPosition), eq(mockOrders));
    }

    @Test
    void testNotifyOrderUpdate() {
        String clientId = "test_order_id";
        String status = "FILLED";
        strategy.webSocketReady.set(true);

        strategy.notifyOrderUpdate(clientId, status);

        verify(orderManager).handleOrderUpdate(eq(clientId));
    }
}
