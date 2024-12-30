package org.tradebot.binance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import org.tradebot.service.TradingBot;
import org.tradebot.domain.*;

import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class RestAPIServiceTest {

    @Mock
    private HttpClientService httpClient;

    @InjectMocks
    private RestAPIService restAPIService;

    @BeforeEach
    void setUp() {
        TradingBot.precision = new Precision(1, 2);
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testPlaceOrder_withAllParams() {
        Order order = new Order();
        order.setSymbol("BTCUSDT");
        order.setSide(Order.Side.BUY);
        order.setType(Order.Type.LIMIT);
        order.setPrice(100.5);
        order.setQuantity(10.0);
        order.setStopPrice(99.5);
        order.setReduceOnly(true);
        order.setClosePosition(false);
        order.setNewClientOrderId("testOrder123");
        order.setTimeInForce(Order.TimeInForce.GTC);

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);

        when(httpClient.sendRequest(anyString(), anyString(), anyMap())).thenReturn("mocked-response");

        restAPIService.placeOrder(order);

        verify(httpClient).sendRequest(eq("/fapi/v1/order"), eq("POST"), captor.capture());
        Map<String, String> params = captor.getValue();

        assertEquals("BTCUSDT", params.get("symbol"));
        assertEquals("BUY", params.get("side"));
        assertEquals("LIMIT", params.get("type"));
        assertEquals("100.50", params.get("price"));
        assertEquals("10.0", params.get("quantity"));
        assertEquals("99.50", params.get("stopPrice"));
        assertEquals("true", params.get("reduceOnly"));
        assertEquals("false", params.get("closePosition"));
        assertEquals("GTC", params.get("timeInForce"));
        assertEquals("testOrder123", params.get("newClientOrderId"));
    }

    @Test
    void testPlaceOrder_withMandatoryParamsOnly() {
        Order order = new Order();
        order.setSymbol("BTCUSDT");
        order.setSide(Order.Side.SELL);
        order.setType(Order.Type.MARKET);
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        when(httpClient.sendRequest(anyString(), anyString(), anyMap())).thenReturn("mocked-response");
        restAPIService.placeOrder(order);

        verify(httpClient).sendRequest(eq("/fapi/v1/order"), eq("POST"), captor.capture());
        Map<String, String> params = captor.getValue();
        assertEquals("BTCUSDT", params.get("symbol"));
        assertEquals("SELL", params.get("side"));
        assertEquals("MARKET", params.get("type"));
        assertFalse(params.containsKey("price"));
        assertFalse(params.containsKey("quantity"));
        assertFalse(params.containsKey("stopPrice"));
        assertFalse(params.containsKey("reduceOnly"));
        assertFalse(params.containsKey("closePosition"));
        assertFalse(params.containsKey("newClientOrderId"));
    }

    @Test
    void testSetLeverage() {
        int leverage = 20;
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        when(httpClient.sendRequest(anyString(), anyString(), anyMap())).thenReturn("mocked-response");
        restAPIService.setLeverage("BTCUSDT", leverage);

        verify(httpClient).sendRequest(eq("/fapi/v1/leverage"), eq("POST"), captor.capture());
        Map<String, String> params = captor.getValue();
        assertEquals("BTCUSDT", params.get("symbol"));
        assertEquals("20", params.get("leverage"));
    }

    @Test
    void testGetLeverage() {
        when(httpClient.sendRequest(anyString(), anyString(), anyMap())).thenReturn("[{\"leverage\": \"10\"}]");
        int leverage = restAPIService.getLeverage("BTCUSDT");
        assertEquals(10, leverage);
    }

    @Test
    void testGetLeverage_zeroLeverage() {
        when(httpClient.sendRequest(anyString(), anyString(), anyMap())).thenReturn("[]");
        int leverage = restAPIService.getLeverage("BTCUSDT");
        assertEquals(-1, leverage);
    }

    @Test
    void testGetAccountBalance() {
        when(httpClient.sendRequest(anyString(), anyString(), anyMap()))
                .thenReturn("[{\"asset\": \"BNFCR\", \"availableBalance\": \"100.5\"}]");
        double balance = restAPIService.getAccountBalance();
        assertEquals(100.5, balance);
    }

    @Test
    void testGetAccountBalance_zeroBalance() {
        when(httpClient.sendRequest(anyString(), anyString(), anyMap()))
                .thenReturn("[{\"asset\": \"BNB\", \"availableBalance\": \"100.5\"}]");
        double balance = restAPIService.getAccountBalance();
        assertEquals(0, balance);
    }

    @Test
    void testGetAccountInfo() {
        when(httpClient.sendRequest(anyString(), anyString(), anyMap()))
                .thenReturn("{\"canTrade\":true,\"totalWalletBalance\":\"150.75\"}");
        AccountInfo accountInfo = restAPIService.getAccountInfo();
        assertTrue(accountInfo.canTrade());
        assertEquals(150.75, accountInfo.availableBalance());
    }

    @Test
    void testGetUserStreamKey() {
        when(httpClient.sendRequest(anyString(), anyString(), anyMap()))
                .thenReturn("{\"listenKey\":\"testListenKey123\"}");
        String streamKey = restAPIService.getUserStreamKey();
        assertEquals("testListenKey123", streamKey);
    }

    @Test
    void testKeepAliveUserStreamKey() {
        when(httpClient.sendRequest(anyString(), anyString(), anyMap())).thenReturn("mocked-response");
        restAPIService.keepAliveUserStreamKey();
        verify(httpClient).sendRequest(eq("/fapi/v1/listenKey"), eq("PUT"), anyMap());
    }

    @Test
    void testRemoveUserStreamKey() {
        when(httpClient.sendRequest(anyString(), anyString(), anyMap())).thenReturn("mocked-response");
        restAPIService.removeUserStreamKey();
        verify(httpClient).sendRequest(eq("/fapi/v1/listenKey"), eq("DELETE"), anyMap());
    }

    @Test
    void testGetOpenPosition() {
        when(httpClient.sendRequest(anyString(), anyString(), anyMap()))
                .thenReturn("[{\"entryPrice\": \"100.5\", \"positionAmt\": \"10.0\"}]");
        Position position = restAPIService.getOpenPosition("BTCUSDT");
        assertNotNull(position);
        assertEquals(100.5, position.getEntryPrice());
        assertEquals(10.0, position.getPositionAmt());
    }

    @Test
    void testGetOpenPosition_emptyList() {
        when(httpClient.sendRequest(anyString(), anyString(), anyMap())).thenReturn("[]");
        Position position = restAPIService.getOpenPosition("BTCUSDT");
        assertNull(position);
    }

    @Test
    void testGetOpenPosition_emptyPosition() {
        when(httpClient.sendRequest(anyString(), anyString(), anyMap()))
                .thenReturn("[{\"entryPrice\": \"0\", \"positionAmt\": \"0\"}]");
        Position position = restAPIService.getOpenPosition("BTCUSDT");
        assertNull(position);
    }

    @Test
    void testGetMarketDataPublicAPI() {
        when(httpClient.sendPublicRequest(anyString()))
                .thenReturn(new HttpResponse(200,
                        "[[6288335309618,\"\",\"93000\",\"92000\",\"\",\"30\"]]"));
        TreeMap<Long, MarketEntry> marketData = restAPIService.getMarketDataPublicAPI("BTCUSDT", "1m", 1);
        assertEquals(1, marketData.size());
        MarketEntry entry = marketData.firstEntry().getValue();
        assertEquals(93000, entry.high());
        assertEquals(92000, entry.low());
        assertEquals(30, entry.volume());
        assertEquals(6288335309618L, marketData.firstEntry().getKey());
    }

    @Test
    void testGetOrderBookPublicAPI() {
        when(httpClient.sendPublicRequest(anyString(), anyBoolean())).thenReturn(new HttpResponse(200,
                "{\"lastUpdateId\": 6288335309618,\"bids\":[[\"90000\",\"4.2\"]],\"asks\":[[\"91000\",\"3.5\"]]}"));

        OrderBook orderBook = restAPIService.getOrderBookPublicAPI("BTCUSDT");

        assertEquals(6288335309618L, orderBook.lastUpdateId());
        assertEquals(1, orderBook.asks().size());
        assertEquals(1, orderBook.bids().size());

        assertEquals(Map.of(91000.,3.5), orderBook.asks());
        assertEquals(Map.of(90000.,4.2), orderBook.bids());

    }
}
