package org.tradebot.binance;

import org.json.JSONArray;
import org.json.JSONObject;
import org.tradebot.domain.OrderBook;
import org.tradebot.listener.OrderBookCallback;
import org.tradebot.util.Log;
import org.tradebot.util.TaskManager;

import java.util.Map;
import java.util.TreeMap;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class OrderBookHandler {
    public static final long DEPTH_UNLOCK_WAIT_TIME_MILLS = 50_000L;

    protected OrderBookCallback callback;
    protected final String symbol;
    protected final Map<Double, Double> bids = new ConcurrentHashMap<>();
    protected final Map<Double, Double> asks = new ConcurrentHashMap<>();
    protected final TreeMap<Long, JSONObject> initializationMessagesQueue = new TreeMap<>();
    private final RestAPIService apiService;

    private long depthEndpointLockTimeMills = -1;
    protected long orderBookLastUpdateId = -1;
    protected OrderBook snapshot;
    protected boolean isOrderBookInitialized = false;

    public OrderBookHandler(String symbol,
                            RestAPIService apiService,
                            TaskManager taskManager) {
        this.symbol = symbol;
        this.apiService = apiService;
        taskManager.create("sout_order_book", this::soutOrderBook, TaskManager.Type.PERIOD, 0, 100, TimeUnit.MILLISECONDS);
        Log.info("service created");
    }

    public void onMessage(JSONObject message) {
        long updateId = message.getLong("u");
        if (!isOrderBookInitialized) {
            initializeOrderBook(updateId, message);
            return;
        }

        if (message.getLong("pu") == orderBookLastUpdateId) {
            updateOrderBook(updateId, message);
        } else {
            isOrderBookInitialized = false;
            Log.info("order book reinitialization required");
        }
    }

    private void initializeOrderBook(long updateId, JSONObject message) {
        if (depthEndpointLockTimeMills != -1 &&
                System.currentTimeMillis() < depthEndpointLockTimeMills + DEPTH_UNLOCK_WAIT_TIME_MILLS) {
            Log.info("/depth endpoint is locked due to rate limit");//TODO
            return;
        }

        initializationMessagesQueue.put(updateId, message);
        if (initializationMessagesQueue.size() <= 10) {
            snapshot = apiService.getOrderBookPublicAPI(symbol);
        } else if (initializationMessagesQueue.size() > 25) {
            initializationMessagesQueue.clear();
            return;
        }

        if (snapshot.lastUpdateId() == 429L) {
            isOrderBookInitialized = false;
            depthEndpointLockTimeMills = System.currentTimeMillis();
            Log.info("rate limit /depth exceed");
            return;
        }
        Log.info("init order book attempt with message :: " + message);

        orderBookLastUpdateId = snapshot.lastUpdateId();
        asks.clear();
        asks.putAll(snapshot.asks());
        bids.clear();
        bids.putAll(snapshot.bids());
        depthEndpointLockTimeMills = -1;

        AtomicReference<Long> startingPoint = new AtomicReference<>();
        initializationMessagesQueue.entrySet().stream()
                .filter(entry -> {
                    long u = entry.getKey();
                    return entry.getValue().getLong("U") <= orderBookLastUpdateId && u >= orderBookLastUpdateId;
                })
                .findFirst()
                .ifPresent(u -> startingPoint.set(u.getKey()));

        if (startingPoint.get() != null) {
            initializationMessagesQueue.entrySet().stream()
                    .filter(entry -> entry.getKey() >= orderBookLastUpdateId)
                    .forEach(entry -> updateOrderBook(entry.getKey(), entry.getValue()));
            isOrderBookInitialized = true;
            Log.info("order book initialized");
        }
    }

    private void updateOrderBook(long updateId, JSONObject data) {
        updateOrderBook(data.getJSONArray("a"), asks);
        updateOrderBook(data.getJSONArray("b"), bids);
        orderBookLastUpdateId = updateId;

        if (isOrderBookInitialized && callback != null)
            callback.notifyOrderBookUpdate(asks, bids);
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

    public void setCallback(OrderBookCallback callback) {
        this.callback = callback;
        Log.info(String.format("callback added %s", callback.getClass().getName()));
    }

    private void soutOrderBook() {
        Map<Double, Double> asksTmpMap = new TreeMap<>(asks);
        Map<Double, Double> bidsTmpMap = new TreeMap<>(bids).descendingMap();

        asksTmpMap = asksTmpMap.entrySet().stream().limit(5).collect(LinkedHashMap::new,
                (map, entry) -> map.put(entry.getKey(), entry.getValue()),
                Map::putAll);
        bidsTmpMap = bidsTmpMap.entrySet().stream().limit(5).collect(LinkedHashMap::new,
                (map, entry) -> map.put(entry.getKey(), entry.getValue()),
                Map::putAll);

//        Log.removeLines(2, Log.ORDER_BOOK_LOGS_PATH);
        Log.info("shorts :: " + asksTmpMap, Log.ORDER_BOOK_LOGS_PATH);
        Log.info("longs :: " + bidsTmpMap, Log.ORDER_BOOK_LOGS_PATH);
    }

    public void logAll() {
        Log.debug(String.format("""
                        symbol: %s
                        callback: %s
                        bids: %s
                        asks: %s
                        depthEndpointLockTimeMills: %d
                        orderBookLastUpdateId: %s
                        isOrderBookInitialized: %s
                        snapshot: %s
                        initializationMessagesQueue: %s
                        """,
                symbol,
                callback,
                bids,
                asks,
                depthEndpointLockTimeMills,
                orderBookLastUpdateId,
                isOrderBookInitialized,
                snapshot,
                initializationMessagesQueue
        ));
    }
}