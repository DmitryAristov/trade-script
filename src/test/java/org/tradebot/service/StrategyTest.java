package org.tradebot.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.tradebot.binance.RestAPIService;
import org.tradebot.binance.WebSocketService;
import org.tradebot.domain.*;
import org.tradebot.util.TaskManager;

import java.util.Map;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class StrategyTest {

    private Strategy strategy;

    @Mock
    private RestAPIService apiService;
    @Mock
    private WebSocketService webSocketService;
    @Mock
    private TaskManager taskManager;

    private final String symbol = "DOGEUSDT";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(apiService.getAccountBalance()).thenReturn(100.0);
        strategy = new Strategy(symbol, 10, apiService, webSocketService, taskManager);
        TradingBot.precision = new Precision(0, 2);
        strategy.orders.putAll(Map.of(
                "position_open_order", new Order(),
                "take_1", new Order(),
                "take_2", new Order(),
                "stop", new Order(),
                "breakeven_stop", new Order(),
                "timeout_stop", new Order())
        );
    }

    @Test
    void testInitialState() {
        assertFalse(strategy.positionOpened);
    }

    @Test
    void testNotifyImbalanceStateUpdateProgress() {
        Imbalance imbalance = mock(Imbalance.class);
        strategy.notifyImbalanceStateUpdate(1000, ImbalanceService.State.PROGRESS, imbalance);

        verify(webSocketService, times(1)).updateUserDataStream(eq(true));
        assertFalse(strategy.positionOpened);
    }

    @Test
    void testNotifyImbalanceStateUpdatePotentialEndPoint() {
        Imbalance imbalance = mock(Imbalance.class);
        when(imbalance.getType()).thenReturn(Imbalance.Type.UP);
        strategy.workingType = WorkingType.WEBSOCKET;
        strategy.notifyOrderBookUpdate(Map.of(0.3, 100.0), Map.of(0.29, 100.0));
        strategy.notifyImbalanceStateUpdate(1000, ImbalanceService.State.POTENTIAL_END_POINT, imbalance);

        verify(apiService, times(1))
                .placeOrder(argThat(argument ->
                        argument.getSide().equals(Order.Side.SELL) &&
                        argument.getType().equals(Order.Type.MARKET) &&
                        argument.getPrice() == null &&
                        argument.getQuantity().doubleValue() == 3166.00 &&
                        argument.getStopPrice() == null &&
                        argument.isClosePosition() == null &&
                        argument.isReduceOnly() == null &&
                        "position_open_order".equals(argument.getNewClientOrderId()) &&
                        argument.getTimeInForce() == null
                ));
    }

    @Test
    void testOpenPosition() {
        Imbalance imbalance = mock(Imbalance.class);
        when(imbalance.getType()).thenReturn(Imbalance.Type.UP);
        when(imbalance.getEndPrice()).thenReturn(0.3);
        strategy.workingType = WorkingType.WEBSOCKET;
        strategy.notifyOrderBookUpdate(Map.of(0.3, 100.0), Map.of(0.29, 100.0));
        strategy.notifyImbalanceStateUpdate(1000, ImbalanceService.State.POTENTIAL_END_POINT, imbalance);

        verify(apiService, times(1))
                .placeOrder(argThat(argument -> argument.getSide().equals(Order.Side.SELL) &&
                        argument.getType().equals(Order.Type.MARKET) &&
                        argument.getPrice() == null &&
                        argument.getQuantity().doubleValue() == 3166.00 &&
                        argument.getStopPrice() == null &&
                        argument.isClosePosition() == null &&
                        argument.isReduceOnly() == null &&
                        "position_open_order".equals(argument.getNewClientOrderId()) &&
                        argument.getTimeInForce() == null
                ));
    }

    @Test
    void testPlaceTakes() {
        Imbalance imbalance = mock(Imbalance.class);
        when(imbalance.getType()).thenReturn(Imbalance.Type.UP);
        when(imbalance.getStartPrice()).thenReturn(0.2);
        when(imbalance.getEndPrice()).thenReturn(0.3);
        when(imbalance.size()).thenReturn(0.1);
        strategy.workingType = WorkingType.WEBSOCKET;

        strategy.notifyOrderBookUpdate(Map.of(0.3, 100.0), Map.of(0.29, 100.0));
        strategy.notifyImbalanceStateUpdate(1000, ImbalanceService.State.POTENTIAL_END_POINT, imbalance);
        strategy.orders.put("position_open_order", new Order());
        strategy.notifyOrderUpdate("position_open_order", "FILLED");

        verify(apiService, times(1)).placeOrder(argThat(argument -> {
            return argument.getSide().equals(Order.Side.BUY) &&
                    argument.getType().equals(Order.Type.LIMIT) &&
                    argument.getPrice().doubleValue() == 0.27 &&
                    argument.getQuantity().doubleValue() == 1583.00 &&
                    argument.getStopPrice() == null &&
                    argument.isClosePosition() == null &&
                    argument.isReduceOnly() &&
                    argument.getTimeInForce() == Order.TimeInForce.GTC;
        }));

        verify(apiService, times(1)).placeOrder(argThat(argument -> {
            return argument.getSide().equals(Order.Side.BUY) &&
                    argument.getType().equals(Order.Type.LIMIT) &&
                    argument.getPrice().doubleValue() == 0.22 &&
                    argument.getQuantity().doubleValue() == 1583.00 &&
                    argument.getStopPrice() == null &&
                    argument.isClosePosition() == null &&
                    argument.isReduceOnly() &&
                    argument.getTimeInForce() == Order.TimeInForce.GTC;
        }));

        verify(apiService, times(1)).placeOrder(argThat(argument -> {
            return argument.getSide().equals(Order.Side.BUY) &&
                    argument.getType().equals(Order.Type.STOP_MARKET) &&
                    argument.getPrice() == null &&
                    argument.getQuantity() == null &&
                    argument.getStopPrice().doubleValue() == 0.30 &&
                    argument.isClosePosition() &&
                    argument.isReduceOnly() == null &&
                    argument.getTimeInForce() == null;
        }));
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
        assertTrue(strategy.orders.keySet().stream().anyMatch("breakeven_stop"::equals));
    }
}
