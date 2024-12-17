package org.tradebot.api_service;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONObject;
import org.tradebot.TradingAPI;
import org.tradebot.domain.MarketEntry;
import org.tradebot.domain.Order;
import org.tradebot.domain.Position;
import org.tradebot.listener.MarketDataListener;
import org.tradebot.util.Log;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class BinanceAPIService extends WebSocketClient implements TradingAPI {
    public static final double MARKET_ORDER_TRADE_FEE = 0.0005;
    public static final double LIMIT_ORDER_TRADE_FEE = 0.00036;

    private final String apiKey = "";
    private final String apiSecret = "";
    private static final String BASE_URL = "https://api.binance.com";
    private final String symbol;
    private final List<MarketDataListener> listeners = new ArrayList<>();

    private static final int MAX_TRADE_QUEUE_SIZE = 500;
    private Deque<JSONObject> activeQueue = new ArrayDeque<>(MAX_TRADE_QUEUE_SIZE);
    private Deque<JSONObject> processingQueue = new ArrayDeque<>(MAX_TRADE_QUEUE_SIZE);
    private WebSocketClient orderBookClient;
    private final Deque<JSONObject> orderBookQueue = new ArrayDeque<>(MAX_TRADE_QUEUE_SIZE);


    public BinanceAPIService(String symbol) throws URISyntaxException {
        super(new URI("wss://fstream.binance.com/ws"));
        this.symbol = symbol;

        try (ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1)) {
            scheduler.scheduleAtFixedRate(this::processTrades, 1, 1, TimeUnit.SECONDS);
        }
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        send(String.format("{\"method\": \"SUBSCRIBE\", \"params\": [\"%s@aggTrade\"], \"id\": 1}", symbol.toLowerCase()));
    }

    @Override
    public void onMessage(String message) {
        JSONObject trade = new JSONObject(message);
        synchronized (activeQueue) {
            if (activeQueue.size() >= MAX_TRADE_QUEUE_SIZE) {
                activeQueue.pollFirst();
            }
            activeQueue.offerLast(trade);
        }
    }

    private void processTrades() {
        Deque<JSONObject> tempQueue;
        synchronized (activeQueue) {
            tempQueue = activeQueue;
            activeQueue = processingQueue;
            processingQueue = tempQueue;
        }

        double minPrice = Double.MAX_VALUE;
        double maxPrice = Double.MIN_VALUE;

        for (JSONObject trade : processingQueue) {
            double price = trade.getDouble("p");
            minPrice = Math.min(minPrice, price);
            maxPrice = Math.max(maxPrice, price);
        }

        processingQueue.clear();

        MarketEntry entry = new MarketEntry(maxPrice, minPrice, 0.0);
        for (MarketDataListener listener : listeners) {
            try {
                listener.notify(System.currentTimeMillis() - 1000, entry);
            } catch (Exception e) {
                Log.debug(e, false);
            }
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        System.out.println("WebSocket closed with exit code " + code + " additional info: " + reason);
    }

    @Override
    public void onError(Exception ex) {
        Log.debug(ex, false);
    }

    private String sendRequest(String endpoint, String method, Map<String, String> params) throws Exception {
        params.put("timestamp", String.valueOf(System.currentTimeMillis()));
        params.put("signature", generateSignature(params));

        URL url = new URI(BASE_URL + endpoint + "?" + getParamsString(params)).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("X-MBX-APIKEY", apiKey);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            return reader.lines().collect(Collectors.joining());
        }
    }

    private String getParamsString(Map<String, String> params) {
        return params.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&"));
    }

    private String generateSignature(Map<String, String> params) throws Exception {
        String queryString = getParamsString(params);
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(apiSecret.getBytes(), "HmacSHA256");
        mac.init(secretKeySpec);
        byte[] hash = mac.doFinal(queryString.getBytes());
        return Base64.getEncoder().encodeToString(hash);
    }

    public void subscribeOrderBook() throws URISyntaxException {
        if (orderBookClient == null) {
            orderBookClient = new WebSocketClient(new URI("wss://fstream.binance.com/ws/" + symbol.toLowerCase() + "@depth20")) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    Log.debug("OrderBook subscription opened");
                }

                @Override
                public void onMessage(String message) {
                    JSONObject orderBookUpdate = new JSONObject(message);
                    synchronized (orderBookQueue) {
                        if (orderBookQueue.size() >= MAX_TRADE_QUEUE_SIZE) {
                            orderBookQueue.pollFirst(); // Удаляем самый старый элемент
                        }
                        orderBookQueue.offerLast(orderBookUpdate); // Добавляем новый элемент
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    Log.debug("OrderBook subscription closed");
                }

                @Override
                public void onError(Exception ex) {
                    Log.debug(ex, false);
                }
            };
            orderBookClient.connect();
        }
    }

    public void unsubscribeOrderBook() {
        if (orderBookClient != null) {
            orderBookClient.close();
            orderBookClient = null;
            Log.debug("OrderBook subscription canceled");
        }
    }

    @Override
    public void setLeverage(int leverage) throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", symbol);
        params.put("leverage", String.valueOf(leverage));

        sendRequest("/fapi/v1/leverage", "POST", params);
    }

    @Override
    public double getAccountBalance() throws Exception {
        String response = sendRequest("/api/v3/account", "GET", new HashMap<>());
        JSONObject json = new JSONObject(response);
        return json.getJSONArray("balances").getJSONObject(0).getDouble("free");
    }

    @Override
    public void createLimitOrder(Order order) throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", symbol);
        params.put("side", order.getType().toString());
        params.put("type", order.getExecutionType().toString());
        params.put("quantity", String.valueOf(order.getMoneyAmount()));
        if (order.getPrice() == null) {
            order.setPrice(getPriceFromOrderBookLevel(5));
        }
        params.put("price", String.valueOf(order.getPrice()));
        params.put("timeInForce", "GTC");
        //TODO implement take profit prices. If 2 take profit prices - close position partially: 0.5 with the first price, 0.5 with the second

        sendRequest("/api/v3/order", "POST", params);
    }

    @Override
    public List<Position> getOpenPositions() throws Exception {
        String response = sendRequest("/api/v3/openOrders", "GET", new HashMap<>());
        JSONArray jsonArray = new JSONArray(response);
        List<Position> positions = new ArrayList<>();
        for (int i = 0; i < jsonArray.length(); i++) {
            positions.add(new Position(jsonArray.getJSONObject(i)));
        }
        return positions;
    }

    @Override
    public TreeMap<Long, MarketEntry> getMarketData(long interval, long lookbackPeriod) throws Exception {
        //TODO implement request for volatility and average
        return new TreeMap<>();
    }

    private double getPriceFromOrderBookLevel(int orderBookLevel) {
        orderBookQueue.getFirst();
        //TODO implement order book websocket subscription and store the last 10 buy and 10 sell orders
        return 0;
    }

    @Override
    public void subscribe(MarketDataListener listener) {
        this.listeners.add(listener);
    }

    @Override
    public void unsubscribe(MarketDataListener listener) {
        this.listeners.remove(listener);
    }
}