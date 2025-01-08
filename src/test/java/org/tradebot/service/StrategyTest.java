package org.tradebot.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.tradebot.binance.RestAPIService;
import org.tradebot.domain.*;
import org.tradebot.util.TaskManager;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.tradebot.service.Strategy.*;
import static org.tradebot.util.OrderUtils.*;

class StrategyTest {

    private static final long ORDER_ID = 1L;
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
    void testOpenPosition() {
        Imbalance imbalance = mock(Imbalance.class);
        when(imbalance.getType()).thenReturn(Imbalance.Type.UP);
        strategy.websocketState.set(true);
        strategy.notifyOrderBookUpdate(Map.of(0.3, 100.0), Map.of(0.29, 100.0));
        Order expected = new Order();
        expected.setId(ORDER_ID);
        expected.setSide(Order.Side.SELL);
        expected.setType(Order.Type.MARKET);
        expected.setQuantity(3166.00);
        expected.setNewClientOrderId(OPEN_POSITION_CLIENT_ID);

        when(apiService.placeOrder(argThat(argument -> argument.getNewClientOrderId().equals(expected.getNewClientOrderId())))).thenReturn(expected);
        strategy.notifyImbalanceStateUpdate(1000, ImbalanceService.State.POTENTIAL_END_POINT, imbalance);

        assertTrue(strategy.openedOrders.containsKey(OPEN_POSITION_CLIENT_ID));
        Order actual = strategy.openedOrders.get(OPEN_POSITION_CLIENT_ID);
        assertEqualsOrders(expected, actual);
    }

    @Test
    void testPlaceTakesAndStop_SuccessWithWebSocket() {
        Imbalance imbalance = mock(Imbalance.class);
        when(imbalance.getType()).thenReturn(Imbalance.Type.UP);
        when(imbalance.getStartPrice()).thenReturn(0.2);
        when(imbalance.getEndPrice()).thenReturn(0.3);
        when(imbalance.size()).thenReturn(0.1);
        strategy.websocketState.set(true);

        strategy.notifyOrderBookUpdate(Map.of(0.3, 100.0), Map.of(0.29, 100.0));

        Order take0 = new Order();
        take0.setId(ORDER_ID);
        take0.setSide(Order.Side.SELL);
        take0.setType(Order.Type.LIMIT);
        take0.setPrice(0.33);
        take0.setQuantity(1500.00);
        take0.setReduceOnly(true);
        take0.setNewClientOrderId(TAKE_CLIENT_ID_PREFIX + 0);
        take0.setTimeInForce(Order.TimeInForce.GTC);

        Order take1 = new Order();
        take1.setId(ORDER_ID);
        take1.setSide(Order.Side.SELL);
        take1.setType(Order.Type.LIMIT);
        take1.setPrice(0.38);
        take1.setQuantity(1500.00);
        take1.setReduceOnly(true);
        take1.setNewClientOrderId(TAKE_CLIENT_ID_PREFIX + 1);
        take1.setTimeInForce(Order.TimeInForce.GTC);

        Order stop = new Order();
        stop.setId(ORDER_ID);
        stop.setSide(Order.Side.SELL);
        stop.setType(Order.Type.STOP_MARKET);
        stop.setPrice(0.30);
        stop.setClosePosition(true);
        stop.setNewClientOrderId(STOP_CLIENT_ID);

        when(apiService.placeOrder(any(Order.class)))
                .thenReturn(stop);
        when(apiService.placeBatchOrders(anyCollection()))
                .thenReturn(List.of(take0, take1));

        strategy.notifyImbalanceStateUpdate(1000, ImbalanceService.State.POTENTIAL_END_POINT, imbalance);
        strategy.openedOrders.put(OPEN_POSITION_CLIENT_ID, new Order());
        Position position = new Position();
        position.setEntryPrice(0.3);
        position.setPositionAmt(3000);
        when(apiService.getOpenPosition(symbol)).thenReturn(position);
        strategy.position.set(position);
        strategy.notifyOrderUpdate(OPEN_POSITION_CLIENT_ID, "FILLED");

        assertTrue(strategy.openedOrders.containsKey(TAKE_CLIENT_ID_PREFIX + 0));
        Order actualTake0 = strategy.openedOrders.get(TAKE_CLIENT_ID_PREFIX + 0);
        assertEqualsOrders(take0, actualTake0);

        assertTrue(strategy.openedOrders.containsKey(TAKE_CLIENT_ID_PREFIX + 1));
        Order actualTake1 = strategy.openedOrders.get(TAKE_CLIENT_ID_PREFIX + 1);
        assertEqualsOrders(take1, actualTake1);

        assertTrue(strategy.openedOrders.containsKey(STOP_CLIENT_ID));
        Order actualStop = strategy.openedOrders.get(STOP_CLIENT_ID);
        assertEqualsOrders(stop, actualStop);
    }

    @Test
    void testPlaceTakesAndStop_SuccessWithTask() {
        Imbalance imbalance = mock(Imbalance.class);
        when(imbalance.getType()).thenReturn(Imbalance.Type.UP);
        when(imbalance.getStartPrice()).thenReturn(0.2);
        when(imbalance.getEndPrice()).thenReturn(0.3);
        when(imbalance.size()).thenReturn(0.1);
        strategy.websocketState.set(true);

        strategy.notifyOrderBookUpdate(Map.of(0.3, 100.0), Map.of(0.29, 100.0));

        Order[] take = new Order[TAKE_PROFIT_THRESHOLDS.length];
        for (int i = 0; i < TAKE_PROFIT_THRESHOLDS.length; i++) {
            take[i] = new Order();
            take[i].setId(ORDER_ID);
            take[i].setSide(Order.Side.SELL);
            take[i].setType(Order.Type.LIMIT);
            take[i].setPrice(0.33);
            take[i].setQuantity(1500.00);
            take[i].setReduceOnly(true);
            take[i].setNewClientOrderId(TAKE_CLIENT_ID_PREFIX + i);
            take[i].setTimeInForce(Order.TimeInForce.GTC);
        }
        take[0].setPrice(0.33);
        take[1].setPrice(0.38);

        Order stop = new Order();
        stop.setId(ORDER_ID);
        stop.setSide(Order.Side.SELL);
        stop.setType(Order.Type.STOP_MARKET);
        stop.setPrice(0.30);
        stop.setClosePosition(true);
        stop.setNewClientOrderId(STOP_CLIENT_ID);

        when(apiService.placeOrder(any(Order.class)))
                .thenReturn(stop)
                .thenReturn(take[0])
                .thenReturn(take[1]);

        strategy.notifyImbalanceStateUpdate(1000, ImbalanceService.State.POTENTIAL_END_POINT, imbalance);
        strategy.openedOrders.put(OPEN_POSITION_CLIENT_ID, new Order());
        Position position = new Position();
        position.setEntryPrice(0.3);
        position.setPositionAmt(3000);
        when(apiService.getOpenPosition(symbol)).thenReturn(position);
        strategy.position.set(position);

        verify(taskManager, times(1)).create(eq("check_stop_order_exists"),
                any(Runnable.class),
                eq(TaskManager.Type.DELAYED),
                eq(TIMEOUT_FORCE_STOP_CREATION),
                eq(-1L),
                eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void testPlaceTakesAndStop_FailureNoPosition() {
        Imbalance imbalance = mock(Imbalance.class);
        when(imbalance.getType()).thenReturn(Imbalance.Type.UP);
        when(imbalance.getStartPrice()).thenReturn(0.2);
        when(imbalance.getEndPrice()).thenReturn(0.3);
        when(imbalance.size()).thenReturn(0.1);
        strategy.websocketState.set(true);
        strategy.currentImbalance = imbalance;

        strategy.notifyOrderBookUpdate(Map.of(0.3, 100.0), Map.of(0.29, 100.0));

        Position position = new Position();
        position.setEntryPrice(0.3);
        position.setPositionAmt(3000);
        strategy.position.set(position);
        when(apiService.getOpenPosition(symbol))
                .thenReturn(null);
        strategy.openedOrders.put(OPEN_POSITION_CLIENT_ID, new Order());
        strategy.notifyImbalanceStateUpdate(1000, ImbalanceService.State.POTENTIAL_END_POINT, imbalance);

        strategy.position.set(null);
        strategy.notifyOrderUpdate(OPEN_POSITION_CLIENT_ID, "FILLED");

        assertEquals(0, strategy.openedOrders.size());
        verify(apiService, never()).placeOrder(any(Order.class));
        verify(taskManager, times(1)).stop(eq("autoclose_position_timer"));
        verify(apiService, times(1)).cancelAllOpenOrders(eq(symbol));
    }

    @Test
    void testPlaceTakesAndStop_FailureNoOpenOrder__alreadyProcessing() {
        Imbalance imbalance = mock(Imbalance.class);
        imbalance.setStartPrice(0.2);
        imbalance.setEndPrice(0.3);
        strategy.websocketState.set(true);
        strategy.currentImbalance = imbalance;

        strategy.notifyOrderBookUpdate(Map.of(0.3, 100.0), Map.of(0.29, 100.0));

        Position position = new Position();
        position.setEntryPrice(0.3);
        position.setPositionAmt(3000);
        when(apiService.getOpenPosition(symbol)).thenReturn(position);
        strategy.position.set(position);
        strategy.notifyImbalanceStateUpdate(1000, ImbalanceService.State.POTENTIAL_END_POINT, imbalance);

        strategy.position.set(null);
        strategy.notifyOrderUpdate(OPEN_POSITION_CLIENT_ID, "FILLED");

        assertEquals(0, strategy.openedOrders.size());
        verify(apiService, never()).placeOrder(any(Order.class));
    }

    @Test
    void testClosePositionWithTimeout() {
        Position position = new Position();
        position.setEntryPrice(0.31);
        position.setPositionAmt(1);
        when(apiService.getOpenPosition(symbol)).thenReturn(position);
        Order stop = new Order();
        stop.setId(ORDER_ID);
        stop.setSide(Order.Side.SELL);
        stop.setType(Order.Type.MARKET);
        stop.setClosePosition(true);
        stop.setNewClientOrderId(TIMEOUT_STOP_CLIENT_ID);
        when(apiService.placeOrder(argThat(argument -> argument.getNewClientOrderId().equals(stop.getNewClientOrderId())))).thenReturn(stop);

        strategy.closePosition(TIMEOUT_STOP_CLIENT_ID);

        assertTrue(strategy.openedOrders.containsKey(TIMEOUT_STOP_CLIENT_ID));
        Order actualStop = strategy.openedOrders.get(TIMEOUT_STOP_CLIENT_ID);
        assertEqualsOrders(stop, actualStop);
    }

    @Test
    void testBreakEvenStop() {
        Position position = new Position();
        position.setEntryPrice(0.31);
        position.setPositionAmt(1);
        when(apiService.getOpenPosition(symbol)).thenReturn(position);
        Order stop = new Order();
        stop.setId(ORDER_ID);
        stop.setSide(Order.Side.SELL);
        stop.setType(Order.Type.STOP_MARKET);
        stop.setStopPrice(0.31);
        stop.setClosePosition(true);
        stop.setNewClientOrderId(BREAK_EVEN_STOP_CLIENT_ID);
        when(apiService.placeOrder(argThat(argument -> argument.getNewClientOrderId().equals(stop.getNewClientOrderId())))).thenReturn(stop);

        strategy.placeBreakEvenStop();

        assertTrue(strategy.openedOrders.containsKey(BREAK_EVEN_STOP_CLIENT_ID));
        Order actualStop = strategy.openedOrders.get(BREAK_EVEN_STOP_CLIENT_ID);
        assertEqualsOrders(stop, actualStop);
    }

    @Test
    void testInitialState_() {
        assertNull(strategy.position.get());
        assertTrue(strategy.openedOrders.isEmpty());
    }

    @Test
    void testNotifyOrderUpdate_StopOrderFilled() {
        strategy.openedOrders.put(STOP_CLIENT_ID, new Order());
        strategy.notifyOrderUpdate(STOP_CLIENT_ID, "FILLED");

        assertTrue(strategy.openedOrders.isEmpty());
    }

    @Test
    void testUpdateOrdersState() {
        Imbalance imbalance = mock(Imbalance.class);
        imbalance.setStartPrice(0.2);
        imbalance.setEndPrice(0.3);
        strategy.currentImbalance = imbalance;

        Position position = new Position();
        position.setEntryPrice(0.31);
        position.setPositionAmt(1);
        strategy.position.set(position);
        when(apiService.getOpenPosition(symbol)).thenReturn(position);
        Order[] take = new Order[TAKE_PROFIT_THRESHOLDS.length];
        for (int i = 0; i < TAKE_PROFIT_THRESHOLDS.length; i++) {
            take[i] = new Order();
            take[i].setId(ORDER_ID);
            take[i].setSide(Order.Side.SELL);
            take[i].setType(Order.Type.LIMIT);
            take[i].setPrice(0.33);
            take[i].setQuantity(1500.00);
            take[i].setReduceOnly(true);
            take[i].setNewClientOrderId(TAKE_CLIENT_ID_PREFIX + i);
            take[i].setTimeInForce(Order.TimeInForce.GTC);
        }
        take[0].setPrice(0.33);
        take[1].setPrice(0.38);

        Order stop = new Order();
        stop.setId(ORDER_ID);
        stop.setSide(Order.Side.SELL);
        stop.setType(Order.Type.STOP_MARKET);
        stop.setPrice(0.30);
        stop.setClosePosition(true);
        stop.setNewClientOrderId(STOP_CLIENT_ID);

        when(apiService.placeOrder(any(Order.class)))
                .thenReturn(stop)
                .thenReturn(take[0])
                .thenReturn(take[1]);

        Order openOrder = new Order();
        openOrder.setNewClientOrderId(OPEN_POSITION_CLIENT_ID);
        openOrder.setStatus(Order.Status.FILLED);
        strategy.openedOrders.put(OPEN_POSITION_CLIENT_ID, openOrder);

        when(apiService.queryOrder(any(Order.class))).thenReturn(openOrder);

        strategy.updateOrdersState();

        verify(apiService, times(1)).queryOrder(openOrder);
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
        verify(taskManager).create(eq("check_orders_api_mode"), any(Runnable.class), eq(TaskManager.Type.PERIOD), eq(0L), eq(10L), eq(TimeUnit.SECONDS));
    }

    public static void assertEqualsOrders(Order expected, Order actual) {
        assertEquals(expected.getId(), actual.getId());
        assertEquals(expected.getSide(), actual.getSide());
        assertEquals(expected.getType(), actual.getType());
        assertEquals(expected.getPrice(), actual.getPrice());
        assertEquals(expected.getQuantity(), actual.getQuantity());
        assertEquals(expected.getStopPrice(), actual.getStopPrice());
        assertEquals(expected.isClosePosition(), actual.isClosePosition());
        assertEquals(expected.isReduceOnly(), actual.isReduceOnly());
        assertEquals(expected.getTimeInForce(), actual.getTimeInForce());
        assertEquals(expected.getNewClientOrderId(), actual.getNewClientOrderId());
    }
}
