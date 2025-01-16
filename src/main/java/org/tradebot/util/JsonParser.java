package org.tradebot.util;

import org.json.JSONArray;
import org.json.JSONObject;
import org.tradebot.domain.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

public class JsonParser {

    public static final double RISK_LEVEL = 0.25;
    public static final String BASE_ASSET = "BNFCR";

    public static OrderBook parseOrderBook(String response) {
        JSONObject snapshotJson = new JSONObject(response);
        Map<Double, Double> asks = parseOrderBook(snapshotJson.getJSONArray("asks"));
        Map<Double, Double> bids = parseOrderBook(snapshotJson.getJSONArray("bids"));
        return new OrderBook(snapshotJson.getLong("lastUpdateId"), asks, bids);
    }

    public static Map<Double, Double> parseOrderBook(JSONArray orders) {
        Map<Double, Double> result = new ConcurrentHashMap<>();
        for (int i = 0; i < orders.length(); i++) {
            JSONArray order = orders.getJSONArray(i);
            double price = order.getDouble(0);
            double quantity = order.getDouble(1);
            result.put(price, quantity);
        }
        return result;
    }

    public static Order parseOrder(String value) {
        JSONObject orderJson = new JSONObject(value);
        return parseOrder(orderJson);
    }

    public static Order parseOrder(JSONObject orderJson) {
        if (orderJson.isEmpty()) {
            return null;
        }

        Order order = new Order();
        order.setSymbol(orderJson.getString("symbol"));
        order.setSide(Order.Side.valueOf(orderJson.getString("side")));
        order.setType(Order.Type.valueOf(orderJson.getString("type")));
        order.setNewClientOrderId(orderJson.getString("clientOrderId"));
        order.setStatus(Order.Status.valueOf(orderJson.getString("status")));
        order.setId(orderJson.getLong("orderId"));
        if (orderJson.has("time")) {
            order.setCreateTime(orderJson.getLong("time"));
        } else if (orderJson.has("updateTime")) {
            order.setCreateTime(orderJson.getLong("updateTime"));
        }

        if (orderJson.has("origQty")) {
            order.setQuantity(orderJson.getDouble("origQty"));
        }
        if (orderJson.has("price")) {
            order.setPrice(orderJson.getDouble("price"));
        }
        if (orderJson.has("stopPrice")) {
            order.setStopPrice(orderJson.getDouble("stopPrice"));
        }
        if (orderJson.has("reduceOnly")) {
            order.setReduceOnly(orderJson.getBoolean("reduceOnly"));
        }
        if (orderJson.has("closePosition")) {
            order.setClosePosition(orderJson.getBoolean("closePosition"));
        }
        if (orderJson.has("timeInForce")) {
            order.setTimeInForce(Order.TimeInForce.valueOf(orderJson.getString("timeInForce")));
        }
        return order;
    }

    public static Position parsePosition(String value) {
        JSONArray jsonArray = new JSONArray(value);
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

    public static AccountInfo parseAccountInfo(String value) {
        JSONObject account = new JSONObject(value);
        return new AccountInfo(account.getBoolean("canTrade"),
                Double.parseDouble(account.getString("totalWalletBalance")));
    }

    public static double parseBalance(String value) {
        double result = 0.;
        JSONArray jsonArray = new JSONArray(value);
        for (int i = 0; i < jsonArray.length(); i++) {
            JSONObject asset = jsonArray.getJSONObject(i);
            if (BASE_ASSET.equals(asset.getString("asset"))) {
                result = Double.parseDouble(jsonArray.getJSONObject(i).getString("availableBalance"));
            }
        }
        return result * RISK_LEVEL; // what part of account balance is for bot?
    }

    public static Integer parseLeverage(String value) {
        JSONArray jsonArray = new JSONArray(value);
        if (jsonArray.isEmpty()) {
            return null;
        } else {
            return Integer.parseInt(jsonArray.getJSONObject(0).getString("leverage"));
        }

    }

    public static TreeMap<Long, MarketEntry> parseResponseMarketData(String response) {
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

    public static Precision parsePrecision(String response, String symbol) {
        JSONObject json = new JSONObject(response);
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
                        quantityPrecision = parsePrecision(stepSize);
                    }
                    if ("PRICE_FILTER".equals(filter.getString("filterType"))) {
                        double tickSize = filter.getDouble("tickSize");
                        pricePrecision = parsePrecision(tickSize);
                    }
                }
                return new Precision(quantityPrecision, pricePrecision);
            }
        }
        return null;
    }

    public static int parsePrecision(double value) {
        String text = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        int index = text.indexOf('.');
        return (index < 0) ? 0 : text.length() - index - 1;
    }

    public static List<Order> parseOrders(String value) {
        JSONArray jsonArray = new JSONArray(value);
        List<Order> orders = new ArrayList<>();
        for (int i = 0; i < jsonArray.length(); i++) {
            orders.add(parseOrder(jsonArray.getJSONObject(i)));
        }
        return orders;
    }

    public static APIError parseAPIError(String error) {
        JSONObject errorJson = new JSONObject(error);
        return new APIError(errorJson.getInt("code"), errorJson.getString("msg"));
    }
}
