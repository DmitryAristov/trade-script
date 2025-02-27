package org.tradebot.binance;

import org.json.JSONArray;
import org.json.JSONObject;
import org.tradebot.domain.OrderBook;
import org.tradebot.listener.OrderBookCallback;
import org.tradebot.listener.OrderBookStateCallback;
import org.tradebot.util.Log;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.tradebot.util.Settings.SYMBOL;

public class OrderBookHandler {
    private final Log log = new Log("order_book/");

    private final PublicAPIService publicAPIService;
    private final Map<Double, Double> bids = new ConcurrentHashMap<>();
    private final Map<Double, Double> asks = new ConcurrentHashMap<>();
    private final TreeMap<Long, JSONObject> initializationMessagesQueue = new TreeMap<>();
    private final List<OrderBookCallback> callbacks = new ArrayList<>();
    private OrderBookStateCallback initializationStateCallback;

    private long orderBookLastUpdateId = -1;
    private OrderBook snapshot;
    private boolean isOrderBookInitialized = false;

    private static OrderBookHandler instance;

    public static OrderBookHandler getInstance() {
        if (instance == null) {
            instance = new OrderBookHandler();
        }
        return instance;
    }

    private OrderBookHandler() {
        this.publicAPIService = PublicAPIService.getInstance();
        log.info("OrderBookHandler initialized");
    }

    public void onMessage(JSONObject message) {
        long updateId = message.getLong("u");
        if (!isOrderBookInitialized) {
            log.info("Order book not initialized. Queuing message for initialization...");
            initializeOrderBook(updateId, message);
            return;
        }

        if (message.getLong("pu") == orderBookLastUpdateId) {
            updateOrderBook(updateId, message);
        } else {
            log.warn("Order book out of sync. Reinitialization required.");
            isOrderBookInitialized = false;
            initializationMessagesQueue.clear();
            initializationStateCallback.notifyOrderBookStateUpdate(false);
        }
    }

    private void initializeOrderBook(long updateId, JSONObject message) {
        initializationMessagesQueue.put(updateId, message);
        log.debug(String.format("Message added to initialization queue: %s", message));

        if (initializationMessagesQueue.size() < 20) {
            snapshot = publicAPIService.getOrderBookPublicAPI(SYMBOL).getResponse();
        } else if (initializationMessagesQueue.size() <= 80) {
            log.info("Applying snapshot and queued updates...");
            orderBookLastUpdateId = snapshot.lastUpdateId();
            asks.clear();
            asks.putAll(snapshot.asks());
            bids.clear();
            bids.putAll(snapshot.bids());
        } else {
            initializationMessagesQueue.clear();
            log.warn("Initialization queue exceeded maximum size. Clearing and retrying...");
            return;
        }

        log.info("Trying to find updates starting message...");
        AtomicReference<Long> startingPoint = new AtomicReference<>();
        initializationMessagesQueue.entrySet().stream()
                .filter(entry -> {
                    long u = entry.getKey();
                    return entry.getValue().getLong("U") <= orderBookLastUpdateId && u >= orderBookLastUpdateId;
                })
                .findFirst()
                .ifPresent(u -> startingPoint.set(u.getKey()));

        if (startingPoint.get() != null) {
            log.info(String.format("Found starting point for updates: %s", startingPoint.get()));
            initializationMessagesQueue.entrySet().stream()
                    .filter(entry -> entry.getKey() >= orderBookLastUpdateId)
                    .forEach(entry -> updateOrderBook(entry.getKey(), entry.getValue()));
            isOrderBookInitialized = true;
            initializationStateCallback.notifyOrderBookStateUpdate(true);
            log.info("Order book successfully initialized.");
        }
    }

    private void updateOrderBook(long updateId, JSONObject data) {
        updateOrderBook(data.getJSONArray("a"), asks);
        updateOrderBook(data.getJSONArray("b"), bids);
        orderBookLastUpdateId = updateId;

        if (isOrderBookInitialized) {
            logOrderBookUpdate();
            callbacks.parallelStream().forEach(callback ->
                    callback.notifyOrderBookUpdate(asks, bids));
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

    public void addCallback(OrderBookCallback callback) {
        this.callbacks.add(callback);
        log.info(String.format("Callback set: %s", callback.getClass().getName()));
    }

    public void removeCallback(OrderBookCallback callback) {
        this.callbacks.remove(callback);
        log.info(String.format("Callback removed: %s", callback.getClass().getName()));
    }

    public void setInitializationStateCallback(OrderBookStateCallback initializationStateCallback) {
        this.initializationStateCallback = initializationStateCallback;
    }

    public Map<Double, Double> getBids(int limit) {
        Map<Double, Double> bidsTmpMap;
        synchronized (bids) {
            bidsTmpMap = new TreeMap<>(bids).descendingMap();
        }
        bidsTmpMap = bidsTmpMap.entrySet().stream().limit(limit).collect(LinkedHashMap::new,
                (map, entry) -> map.put(entry.getKey(), entry.getValue()),
                Map::putAll);
        return bidsTmpMap;
    }

    public Map<Double, Double> getAsks(int limit) {
        Map<Double, Double> asksTmpMap;
        synchronized (asks) {
            asksTmpMap = new TreeMap<>(asks);
        }
        asksTmpMap = asksTmpMap.entrySet().stream().limit(limit).collect(LinkedHashMap::new,
                (map, entry) -> map.put(entry.getKey(), entry.getValue()),
                Map::putAll);
        return asksTmpMap;
    }

    private void logOrderBookUpdate() {
        Map<Double, Double> asksTmpMap = getAsks(5);
        Map<Double, Double> bidsTmpMap = getBids(5);

//        log.removeLines(2);
        log.debug("Top 5 Asks (shorts): " + asksTmpMap);
        log.debug("Top 5 Bids (longs): " + bidsTmpMap);
    }

    public void logAll() {
        try {
            Map<Double, Double> snapshotAsks;
            synchronized (asks) {
                snapshotAsks = new TreeMap<>(asks);
            }
            Map<Double, Double> snapshotBids;
            synchronized (bids) {
                snapshotBids = new TreeMap<>(bids);
            }
            TreeMap<Long, JSONObject> snapshotInitializationMessagesQueue;
            synchronized (initializationMessagesQueue) {
                snapshotInitializationMessagesQueue = new TreeMap<>(initializationMessagesQueue);
            }
            OrderBook snapshotOrderBook = null;
            if (snapshot != null) {
                synchronized (snapshot) {
                    snapshotOrderBook = snapshot;
                }
            }
            log.debug(String.format("""
                            symbol: %s
                            callback: %s
                            readyCallback: %s
                            bids: %s
                            asks: %s
                            orderBookLastUpdateId: %s
                            isOrderBookInitialized: %s
                            initializationMessagesQueue: %s
                            snapshotOrderBook: %s
                            """,
                    SYMBOL,
                    callbacks,
                    initializationStateCallback,
                    snapshotBids,
                    snapshotAsks,
                    orderBookLastUpdateId,
                    isOrderBookInitialized,
                    snapshotInitializationMessagesQueue,
                    snapshotOrderBook
            ));
        } catch (Exception e) {
            log.warn("Failed to write", e);
        }
    }
}