package org.tradebot.api_service;

import org.json.JSONArray;
import org.json.JSONObject;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.tradebot.TradingAPI;
import org.tradebot.domain.MarketEntry;
import org.tradebot.domain.Order;
import org.tradebot.domain.Position;
import org.tradebot.enums.OrderType;
import org.tradebot.listener.MarketDataListener;
import org.tradebot.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class BybitAPIService extends WebSocketClient implements TradingAPI {

    private final String apiKey;
    private final String apiSecret;
    private final String symbol;

    private final ConcurrentLinkedQueue<JSONObject> tradeQueue = new ConcurrentLinkedQueue<>();
    private double minPrice = Double.MAX_VALUE;
    private double maxPrice = Double.MIN_VALUE;
    private double totalVolume = 0.0;
    private long lastUpdateTime = System.currentTimeMillis();

    private static final String BASE_URL = "https://api.bybit.com";

    private final List<MarketDataListener> listeners = new ArrayList<>();

    public BybitAPIService(URI serverUri, String apiKey, String apiSecret, String symbol) {
        super(serverUri);
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.symbol = symbol;

        // Запуск отдельного потока для обработки тиков
        try (ExecutorService executorService = Executors.newSingleThreadExecutor()) {
            executorService.submit(this::processTrades);
        }
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        System.out.println("WebSocket opened");
        send("{\"op\": \"subscribe\", \"args\": [\"trade.BTCUSDT\"]}");
    }

    @Override
    public void onMessage(String message) {
        JSONObject json = new JSONObject(message);
        if (json.has("data")) {
            JSONArray trades = json.getJSONArray("data");
            for (int i = 0; i < trades.length(); i++) {
                JSONObject trade = trades.getJSONObject(i);
                tradeQueue.offer(trade); // Добавляем тик в очередь
            }
        }
    }

    private void processTrades() {
        while (true) {
            try {
                JSONObject trade = tradeQueue.poll();
                if (trade != null) {
                    processTrade(trade);
                }
                Thread.yield();
            } catch (Exception e) {
                Log.debug(e, false);
            }
        }
    }

    private void processTrade(JSONObject trade) {
        double price = trade.getDouble("price");
        double volume = trade.getDouble("size");

        minPrice = Math.min(minPrice, price);
        maxPrice = Math.max(maxPrice, price);
        totalVolume += volume;

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastUpdateTime >= 1000) {

            System.out.println("MarketEntry: High=" + maxPrice + ", Low=" + minPrice + ", Volume=" + totalVolume);

            resetMarketData();
            lastUpdateTime = currentTime;
        }
    }

    private void resetMarketData() {
        minPrice = Double.MAX_VALUE;
        maxPrice = Double.MIN_VALUE;
        totalVolume = 0.0;
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
        params.put("api_key", apiKey);
        params.put("timestamp", String.valueOf(System.currentTimeMillis()));
        params.put("sign", generateSignature(params));

        URL url = new URI(BASE_URL + endpoint).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setDoOutput(true);

        if (method.equals("POST")) {
            try (OutputStream os = conn.getOutputStream()) {
                String postData = params.entrySet().stream()
                        .map(entry -> entry.getKey() + "=" + entry.getValue())
                        .collect(Collectors.joining("&"));
                os.write(postData.getBytes());
            }
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            return reader.lines().collect(Collectors.joining());
        }
    }

    private String generateSignature(Map<String, String> params) throws Exception {
        StringBuilder sb = new StringBuilder();
        params.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                sb.append(entry.getKey()).append("=").append(entry.getValue()).append("&")
        );
        sb.setLength(sb.length() - 1);

        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(apiSecret.getBytes(), "HmacSHA256");
        mac.init(secretKeySpec);
        byte[] hash = mac.doFinal(sb.toString().getBytes());
        return Base64.getEncoder().encodeToString(hash);
    }

    @Override
    public void createLimitOrder(Order order) throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", this.symbol);
        params.put("side", order.getType().toString());
        params.put("order_type", "Limit");
        params.put("qty", String.valueOf(order.getMoneyAmount()));
        if (order.getPrice() == null) {
            order.setPrice(getPriceFromOrderBookLevel(5));
        }
        params.put("price", String.valueOf(order.getPrice()));
        params.put("stop_loss", String.valueOf(order.getStopLossPrice()));
        //TODO
        params.put("take_profit", String.valueOf(order.getTakeProfitPrices()));

        sendRequest("/v2/private/order/create", "POST", params);
    }

    @Override
    public void setLeverage(int leverage) throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", this.symbol);
        params.put("buy_leverage", String.valueOf(leverage));
        params.put("sell_leverage", String.valueOf(leverage));

        sendRequest("/v2/private/position/leverage/save", "POST", params);
    }

    @Override
    public int getLeverage() throws Exception {
        return 0;
    }

    @Override
    public double getAccountBalance() throws Exception {
        Map<String, String> params = new HashMap<>();
        return sendRequest("/v2/private/wallet/balance", "GET", params);
    }

    @Override
    public List<Position> getOpenPositions() throws Exception {
        Map<String, String> params = new HashMap<>();
        return sendRequest("/v2/private/position/list", "GET", params);
    }

    @Override
    public TreeMap<Long, MarketEntry> getMarketData(long interval, long lookbackPeriod) throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", symbol);
        params.put("interval", String.valueOf(interval));
        params.put("limit", String.valueOf(lookbackPeriod));

        String response = sendRequest("/v2/public/kline/list", "GET", params);
        return parseMarketData(response);
    }

    public TreeMap<Long, MarketEntry> parseMarketData(String marketDataResponse) {
        TreeMap<Long, MarketEntry> marketData = new TreeMap<>();
        JSONObject jsonResponse = new JSONObject(marketDataResponse);
        JSONArray resultArray = jsonResponse.getJSONArray("result");

        for (int i = 0; i < resultArray.length(); i++) {
            JSONObject entry = resultArray.getJSONObject(i);
            long openTime = entry.getLong("open_time");
            double high = entry.getDouble("high");
            double low = entry.getDouble("low");
            double volume = entry.getDouble("volume");
            marketData.put(openTime, new MarketEntry(high, low, volume));
        }
        return marketData;
    }

    private double getPriceFromOrderBookLevel(int orderBookLevel) {
        //TODO
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