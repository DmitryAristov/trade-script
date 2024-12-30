package org.tradebot.binance;

import org.json.JSONArray;
import org.json.JSONObject;
import org.tradebot.domain.AccountInfo;
import org.tradebot.domain.Order;
import org.tradebot.domain.Position;
import org.tradebot.util.Log;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
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
        try {
            params.put("timestamp", String.valueOf(System.currentTimeMillis()));
            String signature = generateSignature(params);
            params.put("signature", signature);

            URL url = new URI(BASE_URL + endpoint + "?" + getParamsString(params)).toURL();

            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setRequestProperty("X-MBX-APIKEY", API_KEY);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

            int responseCode = conn.getResponseCode();

            if (responseCode >= 200 && responseCode < 300) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    return reader.lines().collect(Collectors.joining());
                }
            } else {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getErrorStream()))) {
                    String errorResponse = reader.lines().collect(Collectors.joining());
                    throw new IOException("HTTP Error: " + responseCode + ", Message: " + errorResponse);
                }
            }
        } catch (Exception e) {
            Log.debug(e);
            return "";
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



    public String getOrderBookPublicAPI() {
        try {
            URL url = new URI(String.format("https://fapi.binance.com/fapi/v1/depth?symbol=%s&limit=50", SYMBOL.toUpperCase())).toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");

            int responseCode = connection.getResponseCode();
            if (responseCode == 429) {
                return "429";
            }

            BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder response = new StringBuilder();
            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();
            return response.toString();
        } catch (Exception e) {
            Log.debug(e);
            return "";
        }
    }
}
