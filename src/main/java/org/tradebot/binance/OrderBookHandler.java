package org.tradebot.binance;

import org.json.JSONArray;
import org.json.JSONObject;
import org.tradebot.Strategy;
import org.tradebot.listener.OrderBookListener;
import org.tradebot.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.tradebot.TradingBot.SYMBOL;

public class OrderBookHandler {
    public static final long DEPTH_UNLOCK_WAIT_TIME_MILLS = 50_000L;
    private final List<OrderBookListener> listeners = new ArrayList<>();

    private final Map<Double, Double> bids = new ConcurrentHashMap<>();
    private final Map<Double, Double> asks = new ConcurrentHashMap<>();

    private long depthEndpointLockTimeMills = -1;
    private long orderBookLastUpdateId = -1;
    private boolean isOrderBookInitialized = false;
    private boolean firstOrderBookMessageReceived;

    public void start() {
        //TODO
    }

    public void stop() {
        //TODO
    }

    public void onMessage(JSONObject message) {
        try {
            if (!isOrderBookInitialized) {
                initializeOrderBook();
                return;
            }

            long updateId = message.getLong("u");
            if (!firstOrderBookMessageReceived) {
                if (message.getLong("U") <= orderBookLastUpdateId && updateId >= orderBookLastUpdateId) {
                    updateOrderBook(message, updateId);
                    firstOrderBookMessageReceived = true;
                }
                return;
            }

            if (message.getLong("pu") == orderBookLastUpdateId) {
                updateOrderBook(message, updateId);
            } else {
                isOrderBookInitialized = false;
            }
        } catch (Exception e) {
            Log.debug(e);
        }
    }

    private void initializeOrderBook() {
        try {
            if (depthEndpointLockTimeMills != -1 &&
                    System.currentTimeMillis() < depthEndpointLockTimeMills + DEPTH_UNLOCK_WAIT_TIME_MILLS) {
                return;
            }

            URL url = new URI(String.format("https://fapi.binance.com/fapi/v1/depth?symbol=%s&limit=50", SYMBOL.toUpperCase())).toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");

            int responseCode = connection.getResponseCode();
            if (responseCode == 429) {
                isOrderBookInitialized = false;
                depthEndpointLockTimeMills = System.currentTimeMillis();
                return;
            }

            BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder response = new StringBuilder();
            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();

            JSONObject snapshot = new JSONObject(response.toString());
            orderBookLastUpdateId = snapshot.getLong("lastUpdateId");

            parseOrderBook(snapshot.getJSONArray("asks"), asks);
            parseOrderBook(snapshot.getJSONArray("bids"), bids);
            depthEndpointLockTimeMills = -1;
            isOrderBookInitialized = true;
            firstOrderBookMessageReceived = false;
        } catch (Exception e) {
            Log.debug(e);
        }
    }

    private void updateOrderBook(JSONObject data, long u) {
        updateOrderBook(data.getJSONArray("a"), asks);
        updateOrderBook(data.getJSONArray("b"), bids);
        orderBookLastUpdateId = u;

        if (firstOrderBookMessageReceived && isOrderBookInitialized) {
            listeners.forEach(listener -> listener.notify(asks, bids));
        }
    }

    private void updateOrderBook(JSONArray updates, Map<Double, Double> orderBook) {
        for (int i = 0; i < updates.length(); i++) {
            JSONArray update = updates.getJSONArray(i);
            double price = update.getDouble(0);
            double quantity = update.getDouble(1);

            if (quantity == 0) {
                orderBook.remove(price);
            } else {
                orderBook.put(price, quantity);
            }
        }
    }

    private void parseOrderBook(JSONArray orders, Map<Double, Double> orderBook) {
        orderBook.clear();
        for (int i = 0; i < orders.length(); i++) {
            JSONArray order = orders.getJSONArray(i);
            double price = order.getDouble(0);
            double quantity = order.getDouble(1);
            orderBook.put(price, quantity);
        }
    }

    public void subscribe(OrderBookListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void unsubscribe(OrderBookListener listener) {
        listeners.remove(listener);
    }
}
