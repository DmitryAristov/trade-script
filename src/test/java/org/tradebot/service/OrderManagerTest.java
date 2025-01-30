package org.tradebot.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.tradebot.binance.APIService;
import org.tradebot.domain.HTTPResponse;
import org.tradebot.domain.Imbalance;
import org.tradebot.domain.Order;
import org.tradebot.domain.Position;
import org.tradebot.util.OrderUtils;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.tradebot.service.OrderManager.OrderType.STOP;
import static org.tradebot.service.TradingBot.POSITION_LIVE_TIME;

//TODO
class OrderManagerTest {
    private final String symbol = "BTCUSDT";
    private final int leverage = 10;

    private OrderManager orderManager;

    @Mock
    private TaskManager taskManager;

    @Mock
    private APIService apiService;

    @Mock
    private OrderUtils orderUtils;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        orderManager = new OrderManager(taskManager, apiService, symbol, leverage);
    }

    @Test
    void testPlaceOpenOrderSuccessfully() {
        Imbalance imbalance = new Imbalance(1000, 90000., 5000, 93000., Imbalance.Type.UP);
        when(apiService.getAccountBalance()).thenReturn(HTTPResponse.success(200, 10000.));
        Order order = new Order();
        order.setNewClientOrderId("orderId");
        when(apiService.placeOrder(any())).thenReturn(HTTPResponse.success(200, order));

        double price = 30000.0;
        orderManager.placeOpenOrder(imbalance, price);

        verify(apiService, times(1)).placeOrder(any(Order.class));
        assertEquals(Strategy.State.OPEN_ORDER_PLACED, orderManager.getState());
    }

    @Test
    void testPlaceOpenOrderInInvalidState() {
        Imbalance imbalance = new Imbalance(1000, 90000., 5000, 93000., Imbalance.Type.UP);
        when(apiService.getAccountBalance()).thenReturn(HTTPResponse.success(200, 10000.));
        Order order = new Order();
        order.setNewClientOrderId("orderId");

        when(apiService.placeOrder(any(Order.class))).thenReturn(HTTPResponse.success(200, order));

        orderManager.placeOpenOrder(imbalance, 30000.0);
        orderManager.placeOpenOrder(imbalance, 30000.0); // Second call in invalid state

        verify(apiService, times(1)).placeOrder(any(Order.class));
    }

    @Test
    void testHandleOrderUpdateForOpenOrder() {
        when(apiService.getAccountBalance()).thenReturn(HTTPResponse.success(200, 10000.));
        Imbalance imbalance = new Imbalance(1000, 90000., 5000, 93000., Imbalance.Type.UP);
        Position position = new Position();
        position.setPositionAmt(1.);
        position.setEntryPrice(100000.);
        Order order = new Order();
        order.setNewClientOrderId("openOrder123");
        when(apiService.placeOrder(any())).thenReturn(HTTPResponse.success(200, order));
        orderManager.placeOpenOrder(imbalance, 100000.);
        when(apiService.getOpenPosition(symbol)).thenReturn(HTTPResponse.success(200, position));

        orderManager.handleOrderUpdate(order.getNewClientOrderId());

//        assertEquals(Strategy.State.CLOSING_ORDERS_CREATED, orderManager.getState());
//        verify(taskManager, times(1)).schedule(
//                eq(OrderManager.AUTOCLOSE_POSITION_TASK_KEY),
//                any(Runnable.class),
//                eq(Strategy.POSITION_LIVE_TIME),
//                eq(TimeUnit.MINUTES)
//        );
    }

    @Test
    @Disabled
    void testPlaceClosingOrdersSuccessfully() {
        Imbalance imbalance = new Imbalance(1000, 90000., 5000, 93000., Imbalance.Type.UP);
        Position position = new Position();
        position.setPositionAmt(1.);
        position.setEntryPrice(100000.);
        Order order = new Order();
        order.setNewClientOrderId("orderId");
        when(apiService.getAccountBalance()).thenReturn(HTTPResponse.success(200, 10000.));
        when(apiService.placeOrder(any())).thenReturn(HTTPResponse.success(200, order));

        orderManager.placeOpenOrder(imbalance, 100000.);
        orderManager.placeClosingOrdersAndScheduleCloseTask(position);

        verify(apiService, times(4)).placeOrder(any(Order.class));
        verify(taskManager, times(1)).schedule(
                eq(OrderManager.AUTOCLOSE_POSITION_TASK_KEY),
                any(Runnable.class),
                eq(POSITION_LIVE_TIME),
                eq(TimeUnit.MINUTES)
        );
    }

    @Test
    void testPlaceBreakEvenStopSuccessfully() {
        Position position = new Position();
        position.setPositionAmt(1.);
        position.setEntryPrice(100000.);
        Order order = new Order();
        order.setNewClientOrderId("breakevenOrder123");
        when(apiService.placeOrder(any())).thenReturn(HTTPResponse.success(200, order));
        orderManager.getOrders().put(STOP, "stop123");

        orderManager.handleFirstTakeOrderFilled(position);

        verify(apiService, times(1)).placeOrder(any(Order.class));
        verify(apiService, times(1)).cancelOrder(eq(symbol), anyString());
    }

    @Test
    @Disabled
    void testResetToEmptyPosition() {
        orderManager.resetToEmptyPosition(null);

//        verify(taskManager, times(1)).cancel(OrderManager.AUTOCLOSE_POSITION_TASK_KEY);
//        assertEquals(Strategy.State.POSITION_EMPTY, orderManager.getState());
    }

//    @Test
//    void testClosePositionPositionSuccessfully() {
//        Position position = new Position();
//        position.setPositionAmt(1.);
//        position.setEntryPrice(100000.);
//        Order order = new Order();
//        order.setNewClientOrderId("closeOrder123");
//        when(apiService.placeOrder(any())).thenReturn(HTTPResponse.success(200, order));
//
//        orderManager.closePosition();
//
////        verify(apiService, times(1)).placeOrder(any(Order.class));
//    }

//    @Test
//    void testClosePositionTimeoutSuccessfully() {
//        Position position = new Position();
//        position.setPositionAmt(1.);
//        position.setEntryPrice(100000.);
//        Order order = new Order();
//        order.setNewClientOrderId("timeoutOrder123");
//        when(apiService.placeOrder(any())).thenReturn(HTTPResponse.success(200, order));
//
//        orderManager.closeTimeout();
//
////        verify(apiService, times(1)).placeOrder(any(Order.class));
//    }

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