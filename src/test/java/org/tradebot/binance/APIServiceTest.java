//package org.tradebot.binance;
//
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.mockito.*;
//import org.tradebot.domain.*;
//
//import java.io.*;
//import java.util.Map;
//import java.util.TreeMap;
//
//import static org.junit.jupiter.api.Assertions.*;
//import static org.mockito.Mockito.*;
//import static org.tradebot.util.Settings.RISK_LEVEL;
//import static org.tradebot.util.Settings.BASE_ASSET;
//
////TODO
//@SuppressWarnings("unchecked")
//class APIServiceTest {
//
//    @InjectMocks
//    private APIService apiService;
//
//    @Mock
//    private HttpClient httpClient;
//
//    @BeforeEach
//    void setUp() {
//        MockitoAnnotations.openMocks(this);
//        apiService = spy(new APIService(httpClient));
//    }
//
//    @Test
//    void testPlaceOrder_withAllParams() {
//        Order order = new Order();
//        order.setId(123L);
//        order.setSymbol("BTCUSDT");
//        order.setSide(Order.Side.BUY);
//        order.setType(Order.Type.LIMIT);
//        order.setPrice(100.5);
//        order.setQuantity(10.0);
//        order.setStopPrice(99.5);
//        order.setReduceOnly(true);
//        order.setClosePosition(false);
//        order.setNewClientOrderId("testOrder123");
//        order.setTimeInForce(Order.TimeInForce.GTC);
//        order.setStatus(Order.Status.NEW);
//
//        String orderJsonStr = readFile("order.json");
//        when(httpClient.sendRequest(anyString(), anyString(), anyMap(), anyBoolean())).thenReturn(HTTPResponse.success(200,orderJsonStr));
//
//        Order result = apiService.placeOrder(order).getResponse();
//
//        assertEquals(123L, result.getId());
//        assertEquals("BTCUSDT", result.getSymbol());
//        assertEquals(Order.Side.BUY, result.getSide());
//        assertEquals(Order.Type.LIMIT, result.getType());
//        assertEquals(100.50, result.getPrice().doubleValue());
//        assertEquals(10.00, result.getQuantity().doubleValue());
//        assertEquals(99.50, result.getStopPrice().doubleValue());
//        assertTrue(result.isReduceOnly());
//        assertFalse(result.isClosePosition());
//        assertEquals(Order.TimeInForce.GTC, result.getTimeInForce());
//        assertEquals(Order.Status.NEW, result.getStatus());
//        assertEquals("testOrder123", result.getNewClientOrderId());
//    }
//
//    @Test
//    void testPlaceOrder_withMandatoryParamsOnly() {
//        Order order = new Order();
//        order.setId(123L);
//        order.setSymbol("BTCUSDT");
//        order.setSide(Order.Side.BUY);
//        order.setType(Order.Type.LIMIT);
//        order.setNewClientOrderId("testOrder123");
//        order.setTimeInForce(Order.TimeInForce.GTC);
//
//        String orderJsonStr = readFile("order.json");
//        when(httpClient.sendRequest(anyString(), anyString(), anyMap(), anyBoolean())).thenReturn(HTTPResponse.success(200,orderJsonStr));
//
//        Order result = apiService.placeOrder(order).getResponse();
//
//        assertEquals(123L, result.getId());
//        assertEquals("BTCUSDT", result.getSymbol());
//        assertEquals(Order.Side.BUY, result.getSide());
//        assertEquals(Order.Type.LIMIT, result.getType());
//        assertEquals("testOrder123", result.getNewClientOrderId());
//    }
//
//    @Test
//    void testSetLeverage() {
//        int leverage = 20;
//        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
//        when(httpClient.sendRequest(anyString(), anyString(), anyMap())).thenReturn(HTTPResponse.success(200,"mocked-response"));
//        apiService.setLeverage("BTCUSDT", leverage);
//
//        verify(httpClient).sendRequest(eq("/fapi/v1/leverage"), eq("POST"), captor.capture());
//        Map<String, String> params = captor.getValue();
//        assertEquals("BTCUSDT", params.get("symbol"));
//        assertEquals("20", params.get("leverage"));
//    }
//
//    @Test
//    void testGetLeverage() {
//        when(httpClient.sendRequest(anyString(), anyString(), anyMap())).thenReturn(HTTPResponse.success(200,"[{\"leverage\": \"10\"}]"));
//        int leverage = apiService.getLeverage("BTCUSDT").getResponse();
//        assertEquals(10, leverage);
//    }
//
//    @Test
//    void testGetLeverage_zeroLeverage() {
//        when(httpClient.sendRequest(anyString(), anyString(), anyMap())).thenReturn(HTTPResponse.success(200,"[]"));
//        Integer leverage = apiService.getLeverage("BTCUSDT").getResponse();
//        assertNull(leverage);
//    }
//
//    @Test
//    void testGetAccountBalance() {
//        when(httpClient.sendRequest(anyString(), anyString(), anyMap()))
//                .thenReturn(HTTPResponse.success(200,String.format("[{\"asset\": \"%s\", \"availableBalance\": \"100.5\"}]", BASE_ASSET)));
//        double balance = apiService.getAccountBalance().getResponse();
//        assertEquals(100.5 * RISK_LEVEL, balance);
//    }
//
//    @Test
//    void testGetAccountBalance_zeroBalance() {
//        when(httpClient.sendRequest(anyString(), anyString(), anyMap()))
//                .thenReturn(HTTPResponse.success(200,"[{\"asset\": \"BNB\", \"availableBalance\": \"100.5\"}]"));
//        double balance = apiService.getAccountBalance().getResponse();
//        assertEquals(0, balance);
//    }
//
//    @Test
//    void testGetAccountInfo() {
//        when(httpClient.sendRequest(anyString(), anyString(), anyMap()))
//                .thenReturn(HTTPResponse.success(200,"{\"canTrade\":true,\"totalWalletBalance\":\"150.75\"}"));
//        AccountInfo accountInfo = apiService.getAccountInfo().getResponse();
//        assertTrue(accountInfo.canTrade());
//        assertEquals(150.75, accountInfo.availableBalance());
//    }
//
//    @Test
//    void testGetUserStreamKey() {
//        when(httpClient.sendRequest(anyString(), anyString(), anyMap()))
//                .thenReturn(HTTPResponse.success(200,"{\"listenKey\":\"testListenKey123\"}"));
//        String streamKey = apiService.getUserStreamKey().getResponse();
//        assertEquals("testListenKey123", streamKey);
//    }
//
//    @Test
//    void testKeepAliveUserStreamKey() {
//        when(httpClient.sendRequest(anyString(), anyString(), anyMap())).thenReturn(HTTPResponse.success(200,"mocked-response"));
//        apiService.keepAliveUserStreamKey();
//        verify(httpClient).sendRequest(eq("/fapi/v1/listenKey"), eq("PUT"), anyMap());
//    }
//
//    @Test
//    void testRemoveUserStreamKey() {
//        when(httpClient.sendRequest(anyString(), anyString(), anyMap())).thenReturn(HTTPResponse.success(200,"mocked-response"));
//        apiService.removeUserStreamKey();
//        verify(httpClient).sendRequest(eq("/fapi/v1/listenKey"), eq("DELETE"), anyMap());
//    }
//
//    @Test
//    void testGetOpenPosition() {
//        when(httpClient.sendRequest(anyString(), anyString(), anyMap()))
//                .thenReturn(HTTPResponse.success(200,"[{\"entryPrice\": \"100.5\", \"positionAmt\": \"10.0\", \"breakEvenPrice\": \"110.0\"}]"));
//        Position position = apiService.getOpenPosition("BTCUSDT").getResponse();
//        assertNotNull(position);
//        assertEquals(100.5, position.getEntryPrice());
//        assertEquals(10.0, position.getPositionAmt());
//        assertEquals(110.0, position.getBreakEvenPrice());
//    }
//
//    @Test
//    void testGetOpenPosition_emptyList() {
//        when(httpClient.sendRequest(anyString(), anyString(), anyMap())).thenReturn(HTTPResponse.success(200,"[]"));
//        Position position = apiService.getOpenPosition("BTCUSDT").getResponse();
//        assertNull(position);
//    }
//
//    @Test
//    void testGetOpenPosition_emptyPosition() {
//        when(httpClient.sendRequest(anyString(), anyString(), anyMap()))
//                .thenReturn(HTTPResponse.success(200,"[{\"entryPrice\": \"0\", \"positionAmt\": \"0\", \"breakEvenPrice\": \"0\"}]"));
//        Position position = apiService.getOpenPosition("BTCUSDT").getResponse();
//        assertNull(position);
//    }
//
//    @Test
//    void testGetMarketDataPublicAPI() {
//        when(httpClient.sendPublicRequest(anyString(), anyString(), anyMap()))
//                .thenReturn(HTTPResponse.success(200,
//                        "[[6288335309618,\"\",\"93000\",\"92000\",\"\",\"30\"]]"));
//        TreeMap<Long, MarketEntry> marketData = apiService.getMarketDataPublicAPI("BTCUSDT", "1m", 1).getResponse();
//        assertEquals(1, marketData.size());
//        MarketEntry entry = marketData.firstEntry().getValue();
//        assertEquals(93000, entry.high());
//        assertEquals(92000, entry.low());
//        assertEquals(30, entry.volume());
//        assertEquals(6288335309618L, marketData.firstEntry().getKey());
//    }
//
//    @Test
//    void testParseOrderBookPublicAPI() {
//        when(httpClient.sendPublicRequest(anyString(), anyString(), anyMap()))
//                .thenReturn(HTTPResponse.success(200,
//                "{\"lastUpdateId\": 6288335309618,\"bids\":[[\"90000\",\"4.2\"]],\"asks\":[[\"91000\",\"3.5\"]]}"));
//
//        OrderBook orderBook = apiService.getOrderBookPublicAPI("BTCUSDT").getResponse();
//
//        assertEquals(6288335309618L, orderBook.lastUpdateId());
//        assertEquals(1, orderBook.asks().size());
//        assertEquals(1, orderBook.bids().size());
//
//        assertEquals(Map.of(91000., 3.5), orderBook.asks());
//        assertEquals(Map.of(90000., 4.2), orderBook.bids());
//
//    }
//
//    private static String readFile(String fileName) {
//        String filePath = "src/test/resources/" + fileName;
//        File file = new File(filePath);
//        String result = "";
//
//        try {
//            if (file.exists()) {
//                FileReader reader = new FileReader(file);
//                char[] buffer = new char[(int) file.length()];
//                reader.read(buffer);
//                reader.close();
//                result = new String(buffer);
//            } else {
//                result = "";
//            }
//        } catch (IOException _) {
//            return result;
//        }
//        return result;
//    }
//}
