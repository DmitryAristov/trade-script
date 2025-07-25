//package org.tradebot.binance;
//
//import org.json.JSONArray;
//import org.json.JSONObject;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Disabled;
//import org.junit.jupiter.api.Test;
//import org.mockito.InjectMocks;
//import org.mockito.Mock;
//import org.mockito.MockitoAnnotations;
//import org.tradebot.domain.HTTPResponse;
//import org.tradebot.domain.OrderBook;
//import org.tradebot.listener.OrderBookCallback;
//import org.tradebot.listener.ReadyStateCallback;
//
//import java.util.HashMap;
//import java.util.Map;
//import java.util.concurrent.ConcurrentHashMap;
//
//import static org.junit.jupiter.api.Assertions.*;
//import static org.mockito.Mockito.*;
//
//class OrderBookHandlerTest {
//
//    @Mock
//    private APIService apiService;
//
//    @Mock
//    private OrderBookCallback callback;
//
//    @Mock
//    private ReadyStateCallback readyStateCallback;
//
//    @InjectMocks
//    private OrderBookHandler orderBookHandler;
//
//    @BeforeEach
//    void setUp() {
//        MockitoAnnotations.openMocks(this);
//        orderBookHandler = new OrderBookHandler("BTCUSDT", apiService);
//    }
//
//    @Test
//    void testInitializeOrderBook_Failure() {
//        OrderBook mockOrderBook = new OrderBook(123450, new HashMap<>(), new HashMap<>());
//        when(apiService.getOrderBookPublicAPI(anyString())).thenReturn(HTTPResponse.success(200, mockOrderBook));
//        JSONObject message = new JSONObject();
//        message.put("U", 123455);
//        message.put("u", 123456);
//        orderBookHandler.onMessage(message);
//        assertFalse(orderBookHandler.isOrderBookInitialized);
//    }
//
//    @Test
//    @Disabled
//    void testOnMessage_FirstMessage() {
//        Map<Double, Double> bids = new ConcurrentHashMap<>();
//        bids.put(1.0, 2.0);
//        Map<Double, Double> asks = new ConcurrentHashMap<>();
//        asks.put(3.0, 4.0);
//        OrderBook mockOrderBook = new OrderBook(123456, asks, bids);
//
//        when(apiService.getOrderBookPublicAPI(anyString())).thenReturn(HTTPResponse.success(200, mockOrderBook));
//        orderBookHandler.setInitializationStateCallback(readyStateCallback);
//
//        JSONObject message = new JSONObject();
//        message.put("U", 123455);
//        message.put("u", 123457);
//        message.put("a", new JSONArray());
//        message.put("b", new JSONArray());
//        message.put("pu", 123456);
//        orderBookHandler.onMessage(message);
//
//        message.clear();
//        message.put("U", 123456);
//        message.put("u", 123458);
//        message.put("a", new JSONArray());
//        message.put("b", new JSONArray());
//        message.put("pu", 123457);
//        orderBookHandler.onMessage(message);
//        assertTrue(orderBookHandler.isOrderBookInitialized);
//    }
//
//    @Test
//    void testOnMessage_UpdateOrderBook() {
//        orderBookHandler.isOrderBookInitialized = true;
//        orderBookHandler.orderBookLastUpdateId = 123456;
//
//        JSONObject message = new JSONObject();
//        message.put("u", 123457);
//        message.put("pu", 123456);
//        message.put("a", new JSONArray().put(new JSONArray().put(1.1).put(2.2)));
//        message.put("b", new JSONArray().put(new JSONArray().put(1.0).put(2.0)));
//
//        orderBookHandler.onMessage(message);
//
//        assertEquals(2.2, orderBookHandler.asks.get(1.1));
//        assertEquals(2.0, orderBookHandler.bids.get(1.0));
//    }
//}
