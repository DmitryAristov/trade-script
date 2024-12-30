package org.tradebot.binance;

import org.json.JSONArray;
import org.json.JSONObject;
import org.tradebot.domain.OrderBook;
import org.tradebot.listener.OrderBookListener;
import org.tradebot.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class OrderBookHandler {
    public static final long DEPTH_UNLOCK_WAIT_TIME_MILLS = 50_000L;
    private final List<OrderBookListener> listeners = new ArrayList<>();

    private final Map<Double, Double> bids = new ConcurrentHashMap<>();
    private final Map<Double, Double> asks = new ConcurrentHashMap<>();

    private final RestAPIService apiService;

    private long depthEndpointLockTimeMills = -1;
    private long orderBookLastUpdateId = -1;
    private boolean isOrderBookInitialized = false;
    private boolean firstOrderBookMessageReceived;

    public OrderBookHandler(RestAPIService apiService) {
        this.apiService = apiService;
        Log.info("service created");
    }

    public void onMessage(JSONObject message) {
        if (!isOrderBookInitialized) {
            initializeOrderBook();
            return;
        }

        long updateId = message.getLong("u");
        if (!firstOrderBookMessageReceived) {
            if (message.getLong("U") <= orderBookLastUpdateId && updateId >= orderBookLastUpdateId) {
                updateOrderBook(message, updateId);
                firstOrderBookMessageReceived = true;
                Log.info("first order book message");
            }
            return;
        }

        if (message.getLong("pu") == orderBookLastUpdateId) {
            updateOrderBook(message, updateId);
        } else {
            isOrderBookInitialized = false;
        }
    }

    private void initializeOrderBook() {
        if (depthEndpointLockTimeMills != -1 &&
                System.currentTimeMillis() < depthEndpointLockTimeMills + DEPTH_UNLOCK_WAIT_TIME_MILLS) {
            return;
        }

        OrderBook orderBook = apiService.getOrderBookPublicAPI();
        if (orderBook.lastUpdateId() == 429L) {
            isOrderBookInitialized = false;
            depthEndpointLockTimeMills = System.currentTimeMillis();
            return;
        }

        orderBookLastUpdateId = orderBook.lastUpdateId();
        asks.clear();
        asks.putAll(orderBook.asks());
        bids.clear();
        bids.putAll(orderBook.bids());

        depthEndpointLockTimeMills = -1;
        isOrderBookInitialized = true;
        firstOrderBookMessageReceived = false;
    }

    private void updateOrderBook(JSONObject data, long u) {
        updateOrderBook(data.getJSONArray("a"), asks);
        updateOrderBook(data.getJSONArray("b"), bids);
        orderBookLastUpdateId = u;

        if (firstOrderBookMessageReceived && isOrderBookInitialized) {
            listeners.forEach(listener -> listener.notifyOrderBookUpdate(asks, bids));
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

    public void subscribe(OrderBookListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
            Log.info(String.format("listener added %s", listener.getClass().getName()));
        }
    }

    public void unsubscribe(OrderBookListener listener) {
        listeners.remove(listener);
        Log.info(String.format("listener removed %s", listener.getClass().getName()));
    }
}