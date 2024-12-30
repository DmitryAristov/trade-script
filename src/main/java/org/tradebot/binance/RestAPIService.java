package org.tradebot.binance;

import org.json.JSONArray;
import org.json.JSONObject;
import org.tradebot.domain.*;
import org.tradebot.util.Log;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.tradebot.TradingBot.SYMBOL;

public class RestAPIService {

    private static final String API_KEY = "****";
    private static final String API_SECRET = "****";
    private static final String BASE_URL = "https://fapi.binance.com";

    public void placeOrder(Order order) {
        Map<String, String> params = new HashMap<>();

        // mandatory params
        params.put("symbol", SYMBOL.toUpperCase());
        params.put("side", order.getSide().toString());
        params.put("type", order.getType().toString());

        // optional params
        if (order.getPrice() != null) {
            params.put("price", String.valueOf(order.getPrice()));
        }
        if (order.getQuantity() != null) {
            params.put("quantity", String.valueOf(order.getQuantity()));
        }
        if (order.getStopPrice() != null) {
            params.put("stopPrice", String.valueOf(order.getStopPrice()));
        }
        if (order.isReduceOnly() != null) {
            params.put("reduceOnly", String.valueOf(order.isReduceOnly()));
        }
        if (order.isClosePosition() != null) {
            params.put("closePosition", String.valueOf(order.isClosePosition()));
        }
        if (order.getNewClientOrderId() != null) {
            params.put("newClientOrderId", String.valueOf(order.getNewClientOrderId()));
        }

        sendRequest("/fapi/v1/order", "POST", params);
    }

    public void placeOrders(Collection<Order> orders) {
        orders.forEach(this::placeOrder);
    }

    public void setLeverage(int leverage) {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", SYMBOL.toUpperCase());
        params.put("leverage", String.valueOf(leverage));

        sendRequest("/fapi/v1/leverage", "POST", params);
    }

    public int getLeverage() {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", SYMBOL.toUpperCase());
        params.put("recvWindow", "5000");

        String response = sendRequest("/fapi/v2/positionRisk", "GET", params);
        JSONArray jsonArray = new JSONArray(response);
        if (jsonArray.isEmpty()) {
            return -1;
        } else {
            return Integer.parseInt(jsonArray.getJSONObject(0).getString("leverage"));
        }
    }

    public double getAccountBalance() {
        Map<String, String> params = new HashMap<>();
        params.put("recvWindow", "5000");

        double result = 0.;
        String response = sendRequest("/fapi/v2/balance", "GET", params);
        JSONArray jsonArray = new JSONArray(response);
        for (int i = 0; i < jsonArray.length(); i++) {
            JSONObject asset = jsonArray.getJSONObject(i);
            if ("BNFCR".equals(asset.getString("asset"))) {
                result = Double.parseDouble(jsonArray.getJSONObject(i).getString("availableBalance"));
            }
        }

        return result;
    }

    public AccountInfo getAccountInfo() {
        Map<String, String> params = new HashMap<>();
        params.put("recvWindow", "5000");
        params.put("recvWindow", "5000");

        String response = sendRequest("/fapi/v2/account", "GET", params);
        JSONObject account = new JSONObject(response);
        return new AccountInfo(account.getBoolean("canTrade"),
                Double.parseDouble(account.getString("totalWalletBalance")));
    }

    public Position getOpenPosition() {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", SYMBOL.toUpperCase());
        params.put("recvWindow", "5000");

        String response = sendRequest("/fapi/v3/positionRisk", "GET", params);
        JSONArray jsonArray = new JSONArray(response);
        if (jsonArray.isEmpty()) {
            return null;
        } else {
            JSONObject positionJson = jsonArray.getJSONObject(0);
            double entryPrice = Double.parseDouble(positionJson.getString("entryPrice"));
            double positionAmt = Double.parseDouble(positionJson.getString("positionAmt"));
            if (entryPrice == 0 || positionAmt == 0) {
                return null;
            }
            Position result = new Position();
            result.setEntryPrice(entryPrice);
            result.setPositionAmt(positionAmt);
            return result;
        }
    }

    public String getUserStreamKey() {
        Map<String, String> params = new HashMap<>();
        params.put("timestamp", String.valueOf(System.currentTimeMillis()));
        String response = sendRequest("/fapi/v1/listenKey", "POST", params);
        return new JSONObject(response).getString("listenKey");
    }

    public void keepAliveUserStreamKey() {
        Map<String, String> params = new HashMap<>();
        params.put("timestamp", String.valueOf(System.currentTimeMillis()));
        sendRequest("/fapi/v1/listenKey", "PUT", params);
    }

    public void removeUserStreamKey() {
        Map<String, String> params = new HashMap<>();
        params.put("timestamp", String.valueOf(System.currentTimeMillis()));
        sendRequest("/fapi/v1/listenKey", "DELETE", params);
    }

    private String sendRequest(String endpoint, String method, Map<String, String> params) {
        Log.debug(String.format("send %s request to %s with params %s", method, endpoint, params.toString()));
        try {
            params.put("timestamp", String.valueOf(System.currentTimeMillis()));
            String signature = generateSignature(params);
            params.put("signature", signature);

            URL url = new URI(BASE_URL + endpoint + "?" + getParamsString(params)).toURL();

            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method);
            connection.setRequestProperty("X-MBX-APIKEY", API_KEY);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

            Map<String, List<String>> headers = connection.getHeaderFields();
            Log.debug(String.format("headers %s", headers.toString()));

            int responseCode = connection.getResponseCode();
            if (responseCode >= 200 && responseCode < 300) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {

                    String response = reader.lines().collect(Collectors.joining());
                    Log.debug(String.format("response code %d, body %s", responseCode, response));
                    return response;
                }
            } else {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getErrorStream()))) {
                    String errorResponse = reader.lines().collect(Collectors.joining());
                    throw Log.error(String.format("http error %d, message %s", responseCode, errorResponse));
                }
            }
        } catch (Exception e) {
            throw Log.error(e);
        }
    }

    private String generateSignature(Map<String, String> params) throws Exception {
        String queryString = getParamsString(params);
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(API_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(secretKeySpec);
        byte[] hash = mac.doFinal(queryString.getBytes(StandardCharsets.UTF_8));
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    private String getParamsString(Map<String, String> params) {
        return params.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&"));
    }

    public OrderBook getOrderBookPublicAPI() {
        Log.debug("GET order book");
        try {
            URL url = new URI(String.format("%s/fapi/v1/depth?symbol=%s&limit=%d",
                    BASE_URL,
                    SYMBOL.toUpperCase(),
                    50)).toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");

            Map<String, List<String>> headers = connection.getHeaderFields();
            Log.debug(String.format("headers %s", headers.toString()));

            int responseCode = connection.getResponseCode();
            if (responseCode >= 200 && responseCode < 300) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                    String response = reader.lines().collect(Collectors.joining());
                    Log.debug(String.format("response code %d, body %s", responseCode, response));

                    JSONObject snapshot = new JSONObject(response);
                    Map<Double, Double> asks = parseOrderBook(snapshot.getJSONArray("asks"));
                    Map<Double, Double> bids = parseOrderBook(snapshot.getJSONArray("bids"));
                    return new OrderBook(snapshot.getLong("lastUpdateId"), asks, bids);
                }
            } else {
                if (responseCode == 429) {
                    Log.debug("429 got : HTTP rate limit exceed");
                    return new OrderBook(429L, null, null);
                }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getErrorStream()))) {
                    String errorResponse = reader.lines().collect(Collectors.joining());
                    throw Log.error(String.format("http error %d, message %s", responseCode, errorResponse));
                }
            }
        } catch (Exception e) {
            throw Log.error(e);
        }
    }

    private Map<Double, Double> parseOrderBook(JSONArray orders) {
        Map<Double, Double> result = new ConcurrentHashMap<>();
        for (int i = 0; i < orders.length(); i++) {
            JSONArray order = orders.getJSONArray(i);
            double price = order.getDouble(0);
            double quantity = order.getDouble(1);
            result.put(price, quantity);
        }
        return result;
    }

    public TreeMap<Long, MarketEntry> getMarketDataPublicAPI(String interval, int limit) {
        Log.debug("GET market data");
        try {
            URL url = URI.create(String.format("%s/fapi/v1/klines?symbol=%s&limit=%d&interval=%s",
                    BASE_URL,
                    SYMBOL.toUpperCase(),
                    limit,
                    interval)).toURL();

            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");

            Map<String, List<String>> headers = connection.getHeaderFields();
            Log.debug(String.format("headers %s", headers.toString()));

            int responseCode = connection.getResponseCode();
            if (responseCode >= 200 && responseCode < 300) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {

                    String response = reader.lines().collect(Collectors.joining());
                    Log.debug(String.format("response code %d, body %s", responseCode, response));
                    return parseResponseMarketData(response);
                }
            } else {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getErrorStream()))) {
                    String errorResponse = reader.lines().collect(Collectors.joining());
                    throw Log.error(String.format("http error %d, message %s", responseCode, errorResponse));
                }
            }
        } catch (Exception e) {
            throw Log.error(e);
        }
    }

    private TreeMap<Long, MarketEntry> parseResponseMarketData(String response) {
        JSONArray klines = new JSONArray(response);
        TreeMap<Long, MarketEntry> marketData = new TreeMap<>();

        for (int i = 0; i < klines.length(); i++) {
            JSONArray candle = klines.getJSONArray(i);
            double high = candle.getDouble(2);
            double low = candle.getDouble(3);
            double volume = candle.getDouble(5);
            MarketEntry entry = new MarketEntry(high, low, volume);
            marketData.put(candle.getLong(0), entry);
        }
        return marketData;
    }
}
