package org.tradebot.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.tradebot.binance.RestAPIService;
import org.tradebot.domain.*;
import org.tradebot.util.TaskManager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class StrategyTest {

    private Strategy strategy;

    @Mock
    private RestAPIService apiService;
    @Mock
    private TaskManager taskManager;

    private final String symbol = "DOGEUSDT";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(apiService.getAccountBalance()).thenReturn(100.0);
        strategy = new Strategy(symbol, 10, apiService, taskManager);
        TradingBot.precision = new Precision(0, 2);
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
        Position position = new Position();
        position.setEntryPrice(0.3);
        position.setPositionAmt(3000);
        when(apiService.getOpenPosition(symbol)).thenReturn(position);
        strategy.position.set(position);
        strategy.notifyOrderUpdate("position_open_order", "FILLED");

        verify(apiService, times(1)).placeOrder(argThat(argument ->
                argument.getSide().equals(Order.Side.SELL) &&
                argument.getType().equals(Order.Type.LIMIT) &&
                argument.getPrice().doubleValue() == 0.33 &&
                argument.getQuantity().doubleValue() == 1500.00 &&
                argument.getStopPrice() == null &&
                argument.isClosePosition() == null &&
                argument.isReduceOnly() &&
                argument.getTimeInForce() == Order.TimeInForce.GTC));

        verify(apiService, times(1)).placeOrder(argThat(argument ->
                argument.getSide().equals(Order.Side.SELL) &&
                argument.getType().equals(Order.Type.LIMIT) &&
                argument.getPrice().doubleValue() == 0.38 &&
                argument.getQuantity().doubleValue() == 1500.00 &&
                argument.getStopPrice() == null &&
                argument.isClosePosition() == null &&
                argument.isReduceOnly() &&
                argument.getTimeInForce() == Order.TimeInForce.GTC));

        verify(apiService, times(1)).placeOrder(argThat(argument ->
                argument.getSide().equals(Order.Side.SELL) &&
                argument.getType().equals(Order.Type.STOP_MARKET) &&
                argument.getPrice() == null &&
                argument.getQuantity() == null &&
                argument.getStopPrice().doubleValue() == 0.30 &&
                argument.isClosePosition() &&
                argument.isReduceOnly() == null &&
                argument.getTimeInForce() == null));
    }

    @Test
    void testClosePositionWithTimeout() {
        Position position = new Position();
        position.setEntryPrice(0.31);
        position.setPositionAmt(1);
        when(apiService.getOpenPosition(symbol)).thenReturn(position);

        strategy.closePositionWithTimeout();

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
        Position position = new Position();
        position.setEntryPrice(0.31);
        position.setPositionAmt(1);
        when(apiService.getOpenPosition(symbol)).thenReturn(position);
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

    @Test
    void testInitialState_() {
        assertNull(strategy.position.get());
        assertTrue(strategy.orders.isEmpty());
    }

    @Test
    void testNotifyImbalanceStateUpdate_PotentialEndPoint() {
        Imbalance imbalance = mock(Imbalance.class);
        when(apiService.getAccountBalance()).thenReturn(100.0);
        when(imbalance.getType()).thenReturn(Imbalance.Type.UP);

        Map<Double, Double> asks = Map.of(10.0, 100.0);
        strategy.notifyOrderBookUpdate(asks, new ConcurrentHashMap<>());

        strategy.notifyImbalanceStateUpdate(1000L, ImbalanceService.State.POTENTIAL_END_POINT, imbalance);

        verify(apiService).placeOrder(any(Order.class));
        assertFalse(strategy.orders.isEmpty());
    }

    @Test
    void testOpenPosition_InvalidPriceOrQuantity() {
        Imbalance imbalance = mock(Imbalance.class);
        when(apiService.getAccountBalance()).thenReturn(0.0);
        when(imbalance.getType()).thenReturn(Imbalance.Type.UP);

        Map<Double, Double> asks = Map.of(0.0, 100.0);
        strategy.notifyOrderBookUpdate(asks, new ConcurrentHashMap<>());

        Exception exception = assertThrows(RuntimeException.class, () ->
                strategy.notifyImbalanceStateUpdate(1000L, ImbalanceService.State.POTENTIAL_END_POINT, imbalance));

        assertTrue(exception.getMessage().contains("Invalid price or quantity"));
    }

    @Test
    void testNotifyOrderUpdate_OpenOrderFilled() {
        Imbalance imbalance = mock(Imbalance.class);
        when(imbalance.getType()).thenReturn(Imbalance.Type.UP);
        strategy.currentImbalance = imbalance;
        Position position = new Position();
        position.setEntryPrice(0.31);
        position.setPositionAmt(1);
        strategy.position.set(position);
        when(apiService.getOpenPosition(symbol)).thenReturn(position);

        strategy.orders.put("position_open_order", new Order());
        strategy.notifyOrderUpdate("position_open_order", "FILLED");

        verify(taskManager).create(eq("autoclose_position_timer"), any(Runnable.class), eq(TaskManager.Type.ONCE), eq(4L), eq(-1L), eq(TimeUnit.HOURS));
    }

    @Test
    void testNotifyOrderUpdate_TakeOrderFilled() {
        strategy.orders.put("take_0", new Order());
        Position position = new Position();
        position.setEntryPrice(0.31);
        position.setPositionAmt(1);
        strategy.position.set(position);
        when(apiService.getOpenPosition(symbol)).thenReturn(position);
        strategy.notifyOrderUpdate("take_0", "FILLED");

        verify(apiService).placeOrder(any(Order.class));
    }

    @Test
    void testNotifyOrderUpdate_StopOrderFilled() {
        strategy.orders.put("stop", new Order());
        strategy.notifyOrderUpdate("stop", "FILLED");

        assertTrue(strategy.orders.isEmpty());
    }

    @Test
    void testUpdateOrdersState() {
        Position position = new Position();
        position.setEntryPrice(0.31);
        position.setPositionAmt(1);
        strategy.position.set(position);
        when(apiService.getOpenPosition(symbol)).thenReturn(position);

        Order openOrder = new Order();
        openOrder.setNewClientOrderId("position_open_order");
        openOrder.setStatus(Order.Status.FILLED);

        strategy.orders.put("position_open_order", openOrder);
        when(apiService.queryOrder(any(Order.class))).thenReturn(openOrder);

        strategy.updateOrdersState();

        verify(apiService, times(1)).queryOrder(openOrder);
    }

    @Test
    void testPlaceClosingOrders_Successful() {
        Position position = new Position();
        position.setEntryPrice(0.31);
        position.setPositionAmt(1);
        strategy.position.set(position);
        strategy.currentImbalance = mock(Imbalance.class);
        when(apiService.getOpenPosition(symbol)).thenReturn(position);

        strategy.placeClosingOrders();

        verify(apiService, times(3)).placeOrder(any(Order.class));
    }

    @Test
    void testPlaceClosingOrders_Failure() {
        Position position = new Position();
        position.setEntryPrice(0.31);
        position.setPositionAmt(1);
        strategy.position.set(position);
        strategy.currentImbalance = mock(Imbalance.class);
        when(apiService.getOpenPosition(symbol)).thenReturn(position);

        when(apiService.placeOrder(argThat(order -> order.getNewClientOrderId().equals("stop")))).thenThrow(new RuntimeException("Failed"));

        strategy.placeClosingOrders();

        verify(apiService, atLeast(3)).placeOrder(argThat(order -> order.getNewClientOrderId().equals("stop")));
    }

    @Test
    void testClosePositionWithTimeout_() {
        Position position = new Position();
        position.setEntryPrice(0.31);
        position.setPositionAmt(1);
        strategy.position.set(position);
        when(apiService.getOpenPosition(symbol)).thenReturn(position);

        strategy.closePositionWithTimeout();

        verify(apiService).placeOrder(any(Order.class));
    }

    @Test
    void testNotifyWebsocketStateChanged_WebsocketReady() {
        strategy.notifyWebsocketStateChanged(true);
        verify(taskManager).stop("check_orders_api_mode");
    }

    @Test
    void testNotifyWebsocketStateChanged_WebsocketNotReady() {
        strategy.position.set(new Position());
        strategy.notifyWebsocketStateChanged(false);
        verify(taskManager).create(eq("check_orders_api_mode"), any(Runnable.class), eq(TaskManager.Type.PERIOD), eq(0L), eq(1L), eq(TimeUnit.SECONDS));
    }
}
