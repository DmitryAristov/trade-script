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

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(apiService.getAccountBalance()).thenReturn(100.0);
        strategy = new Strategy(symbol, 10, apiService, webSocketService);
        TradingBot.precision = new Precision(0, 2);
    }

    @Test
    void testInitialState() {
        assertFalse(strategy.positionOpened);
    }

    @Test
    void testNotifyImbalanceStateUpdateProgress() {
        Imbalance imbalance = mock(Imbalance.class);
        strategy.notifyImbalanceStateUpdate(1000, ImbalanceService.State.PROGRESS, imbalance);

        verify(webSocketService, times(1)).openUserDataStream();
        assertFalse(strategy.positionOpened);
        assertEquals(0, strategy.orders.size());
    }

    @Test
    void testNotifyImbalanceStateUpdatePotentialEndPoint() {
        Imbalance imbalance = mock(Imbalance.class);
        when(imbalance.getType()).thenReturn(Imbalance.Type.UP);
        strategy.notifyOrderBookUpdate(Map.of(0.3, 100.0), Map.of(0.29, 100.0));
        strategy.notifyImbalanceStateUpdate(1000, ImbalanceService.State.POTENTIAL_END_POINT, imbalance);

        verify(apiService, times(1))
                .placeOrder(argThat(argument -> argument.getSide().equals(Order.Side.SELL) &&
                        argument.getType().equals(Order.Type.MARKET) &&
                        argument.getPrice() == null &&
                        argument.getQuantity().doubleValue() == 3300.00 &&
                        argument.getStopPrice() == null &&
                        argument.isClosePosition() == null &&
                        argument.isReduceOnly() == null &&
                        "position_open_order".equals(argument.getNewClientOrderId()) &&
                        argument.getTimeInForce() == null
                ));
        assertEquals(4, strategy.orders.size());
        assertTrue(strategy.orders.keySet().stream().anyMatch("take_1"::equals));
        assertTrue(strategy.orders.keySet().stream().anyMatch("take_2"::equals));
        assertTrue(strategy.orders.keySet().stream().anyMatch("stop"::equals));
        assertTrue(strategy.orders.keySet().stream().anyMatch("position_open_order"::equals));
    }

    @Test
    void testOpenPosition() {
        Imbalance imbalance = mock(Imbalance.class);
        when(imbalance.getType()).thenReturn(Imbalance.Type.UP);
        when(imbalance.getEndPrice()).thenReturn(0.3);
        strategy.notifyOrderBookUpdate(Map.of(0.3, 100.0), Map.of(0.29, 100.0));
        strategy.notifyImbalanceStateUpdate(1000, ImbalanceService.State.POTENTIAL_END_POINT, imbalance);

        verify(apiService, times(1))
                .placeOrder(argThat(argument -> argument.getSide().equals(Order.Side.SELL) &&
                        argument.getType().equals(Order.Type.MARKET) &&
                        argument.getPrice() == null &&
                        argument.getQuantity().doubleValue() == 3300.00 &&
                        argument.getStopPrice() == null &&
                        argument.isClosePosition() == null &&
                        argument.isReduceOnly() == null &&
                        "position_open_order".equals(argument.getNewClientOrderId()) &&
                        argument.getTimeInForce() == null
                ));
        assertEquals(4, strategy.orders.size());
        assertTrue(strategy.orders.keySet().stream().anyMatch("take_1"::equals));
        assertTrue(strategy.orders.keySet().stream().anyMatch("take_2"::equals));
        assertTrue(strategy.orders.keySet().stream().anyMatch("stop"::equals));
        assertTrue(strategy.orders.keySet().stream().anyMatch("position_open_order"::equals));
    }

    @Test
    void testCreateTakeAndStopOrders() {
        Imbalance imbalance = mock(Imbalance.class);
        when(imbalance.getType()).thenReturn(Imbalance.Type.UP);
        when(imbalance.getStartPrice()).thenReturn(0.2);
        when(imbalance.getEndPrice()).thenReturn(0.3);
        when(imbalance.size()).thenReturn(0.1);

        strategy.notifyOrderBookUpdate(Map.of(0.3, 100.0), Map.of(0.29, 100.0));
        strategy.notifyImbalanceStateUpdate(1000, ImbalanceService.State.POTENTIAL_END_POINT, imbalance);
        strategy.notifyOrderUpdate("position_open_order", "FILLED");

        verify(apiService, times(1)).placeOrders(argThat(argument -> {
            int size = argument.size();
            Order take1 = argument.stream()
                    .filter(order -> "take_1".equals(order.getNewClientOrderId()))
                    .findFirst()
                    .orElseThrow();
            Order take2 = argument.stream()
                    .filter(order -> "take_2".equals(order.getNewClientOrderId()))
                    .findFirst()
                    .orElseThrow();
            Order stop = argument.stream()
                    .filter(order -> "stop".equals(order.getNewClientOrderId()))
                    .findFirst()
                    .orElseThrow();
            boolean take1ParamsMatcher = take1.getSide().equals(Order.Side.BUY) &&
                    take1.getType().equals(Order.Type.LIMIT) &&
                    take1.getPrice().doubleValue() == 0.27 &&
                    take1.getQuantity().doubleValue() == 1650.00 &&
                    take1.getStopPrice() == null &&
                    take1.isClosePosition() == null &&
                    take1.isReduceOnly() &&
                    take1.getTimeInForce() == Order.TimeInForce.GTC;
            boolean take2ParamsMatcher = take2.getSide().equals(Order.Side.BUY) &&
                    take2.getType().equals(Order.Type.LIMIT) &&
                    take2.getPrice().doubleValue() == 0.22 &&
                    take2.getQuantity().doubleValue() == 1650.00 &&
                    take2.getStopPrice() == null &&
                    take2.isClosePosition() == null &&
                    take2.isReduceOnly() &&
                    take2.getTimeInForce() == Order.TimeInForce.GTC;
            boolean stopParamsMatcher = stop.getSide().equals(Order.Side.BUY) &&
                    stop.getType().equals(Order.Type.STOP_MARKET) &&
                    stop.getPrice() == null &&
                    stop.getQuantity() == null &&
                    stop.getStopPrice().doubleValue() == 0.30 &&
                    stop.isClosePosition() &&
                    stop.isReduceOnly() == null &&
                    stop.getTimeInForce() == null;
            return size == 3 && take1ParamsMatcher && take2ParamsMatcher && stopParamsMatcher;
        }));
        assertEquals(3, strategy.orders.size());
        assertTrue(strategy.orders.keySet().stream().anyMatch("take_1"::equals));
        assertTrue(strategy.orders.keySet().stream().anyMatch("take_2"::equals));
        assertTrue(strategy.orders.keySet().stream().anyMatch("stop"::equals));
    }

    @Test
    void testClosePositionByTimeout() {
        Position mockPosition = new Position();
        mockPosition.setEntryPrice(0.31);
        mockPosition.setPositionAmt(1);
        when(apiService.getOpenPosition(symbol)).thenReturn(mockPosition);

        strategy.closePositionByTimeout();

        verify(apiService).placeOrder(argThat(argument -> argument.getQuantity() == null &&
                argument.getSide().equals(Order.Side.SELL) &&
                argument.getType().equals(Order.Type.MARKET) &&
                argument.isClosePosition() &&
                argument.isReduceOnly() == null &&
                "timeout_stop".equals(argument.getNewClientOrderId()) &&
                argument.getTimeInForce() == null));
        assertEquals(1, strategy.orders.size());
        assertTrue(strategy.orders.keySet().stream().anyMatch("timeout_stop"::equals));
    }

    @Test
    void testBreakEvenStop() {
        Position mockPosition = new Position();
        mockPosition.setEntryPrice(0.31);
        mockPosition.setPositionAmt(1);
        when(apiService.getOpenPosition(symbol)).thenReturn(mockPosition);
        strategy.placeBreakEvenStop();

        verify(apiService).placeOrder(argThat(argument -> argument.getQuantity() == null &&
                argument.getSide().equals(Order.Side.SELL) &&
                argument.getType().equals(Order.Type.STOP_MARKET) &&
                argument.getPrice() == null &&
                argument.getStopPrice().doubleValue() == 0.31 &&
                argument.isClosePosition() &&
                argument.isReduceOnly() == null &&
                "breakeven_stop".equals(argument.getNewClientOrderId()) &&
                argument.getTimeInForce() == null));
        assertEquals(1, strategy.orders.size());
        assertTrue(strategy.orders.keySet().stream().anyMatch("breakeven_stop"::equals));
    }

    @Test
    void testStopClosePositionTimer() {
        strategy.stopClosePositionTimer();

        assertNull(strategy.closePositionTimer);
    }
}
