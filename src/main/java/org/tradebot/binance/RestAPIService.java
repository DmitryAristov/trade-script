package org.tradebot.binance;

import org.json.JSONArray;
import org.json.JSONObject;
import org.tradebot.domain.AccountInfo;
import org.tradebot.domain.HttpResponse;
import org.tradebot.domain.MarketEntry;
import org.tradebot.domain.Order;
import org.tradebot.domain.OrderBook;
import org.tradebot.domain.Position;
import org.tradebot.domain.Precision;
import org.tradebot.util.Log;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

public class RestAPIService {
    private static final String BASE_URL = "https://fapi.binance.com";
    private final HttpClientService httpClient;

    public RestAPIService(HttpClientService httpClient) {
        this.httpClient = httpClient;
    }

    public void placeOrder(Order order) {
        Map<String, String> params = new HashMap<>();

        // mandatory params
        params.put("symbol", order.getSymbol());
        params.put("side", order.getSide().toString());
        params.put("type", order.getType().toString());
        if (order.getType() == Order.Type.LIMIT && order.getTimeInForce() == null) {
            throw Log.error("timeInForce required for LIMIT order");
        }

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
        if (order.getTimeInForce() != null) {
            params.put("timeInForce", String.valueOf(order.getTimeInForce()));
        }

        httpClient.sendRequest("/fapi/v1/order", "POST", params);
    }

    public void placeOrders(Collection<Order> orders) {
        orders.forEach(this::placeOrder);
    }

    public void setLeverage(String symbol, int leverage) {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", symbol);
        params.put("leverage", String.valueOf(leverage));

        httpClient.sendRequest("/fapi/v1/leverage", "POST", params);
    }

    public int getLeverage(String symbol) {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", symbol);
        params.put("recvWindow", "5000");

        String response = httpClient.sendRequest("/fapi/v2/positionRisk", "GET", params);
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
        String response = httpClient.sendRequest("/fapi/v2/balance", "GET", params);
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

        String response = httpClient.sendRequest("/fapi/v2/account", "GET", params);
        JSONObject account = new JSONObject(response);
        return new AccountInfo(account.getBoolean("canTrade"),
                Double.parseDouble(account.getString("totalWalletBalance")));
    }

    public Position getOpenPosition(String symbol) {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", symbol);
        params.put("recvWindow", "5000");

        String response = httpClient.sendRequest("/fapi/v3/positionRisk", "GET", params);
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
        String response = httpClient.sendRequest("/fapi/v1/listenKey", "POST", params);
        return new JSONObject(response).getString("listenKey");
    }

    public void keepAliveUserStreamKey() {
        Map<String, String> params = new HashMap<>();
        params.put("timestamp", String.valueOf(System.currentTimeMillis()));
        httpClient.sendRequest("/fapi/v1/listenKey", "PUT", params);
    }

    public void removeUserStreamKey() {
        Map<String, String> params = new HashMap<>();
        params.put("timestamp", String.valueOf(System.currentTimeMillis()));
        httpClient.sendRequest("/fapi/v1/listenKey", "DELETE", params);
    }

    public OrderBook getOrderBookPublicAPI(String symbol) {
        HttpResponse response = httpClient
                .sendPublicRequest(String.format("%s/fapi/v1/depth?symbol=%s&limit=%d", BASE_URL, symbol, 50), true);

        if (response.statusCode() == 429) {
            Log.error("429 got : HTTP rate limit exceed");
            return new OrderBook(429L, null, null);
        }
        if (response.statusCode() >= 300) {
            throw Log.error(response.toString());
        }

        JSONObject snapshot = new JSONObject(response.body());
        Map<Double, Double> asks = parseOrderBook(snapshot.getJSONArray("asks"));
        Map<Double, Double> bids = parseOrderBook(snapshot.getJSONArray("bids"));
        return new OrderBook(snapshot.getLong("lastUpdateId"), asks, bids);
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

    public TreeMap<Long, MarketEntry> getMarketDataPublicAPI(String symbol, String interval, int limit) {
        HttpResponse response = httpClient
                .sendPublicRequest(String.format("%s/fapi/v1/klines?symbol=%s&limit=%d&interval=%s", BASE_URL, symbol, limit, interval));

        return parseResponseMarketData(response.body());
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

    public Precision fetchSymbolPrecision(String symbol) {
        HttpResponse response = httpClient
                .sendPublicRequest(String.format("%s/fapi/v1/exchangeInfo", BASE_URL));

        JSONObject json = new JSONObject(response.body());
        JSONArray symbols = json.getJSONArray("symbols");
        for (int i = 0; i < symbols.length(); i++) {
            JSONObject symbolInfo = symbols.getJSONObject(i);
            if (symbolInfo.getString("symbol").equals(symbol.toUpperCase())) {
                JSONArray filters = symbolInfo.getJSONArray("filters");
                int quantityPrecision = 0;
                int pricePrecision = 0;
                for (int j = 0; j < filters.length(); j++) {
                    JSONObject filter = filters.getJSONObject(j);
                    if ("LOT_SIZE".equals(filter.getString("filterType"))) {
                        double stepSize = filter.getDouble("stepSize");
                        quantityPrecision = getPrecision(stepSize);
                    }
                    if ("PRICE_FILTER".equals(filter.getString("filterType"))) {
                        double tickSize = filter.getDouble("tickSize");
                        pricePrecision = getPrecision(tickSize);
                    }
                }
                return new Precision(quantityPrecision, pricePrecision);
            }
        }
        throw Log.error("precision not found");
    }

    private int getPrecision(double value) {
        String text = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        int index = text.indexOf('.');
        return (index < 0) ? 0 : text.length() - index - 1;
    }
}
