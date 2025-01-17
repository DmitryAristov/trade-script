package org.tradebot.binance;

import org.json.JSONArray;
import org.json.JSONObject;
import org.tradebot.domain.OrderBook;
import org.tradebot.listener.OrderBookCallback;
import org.tradebot.listener.ReadyStateCallback;
import org.tradebot.service.TaskManager;
import org.tradebot.util.Log;

import java.util.Map;
import java.util.TreeMap;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class OrderBookHandler {
    public static final long DEPTH_UNLOCK_WAIT_TIME_MILLS = 50_000L;
    public static final String LOG_ORDER_BOOK_MESSAGES_TASK_KEY = "log_order_book_update";
    private final Log log = new Log("order_book_handler.log");

    private final APIService apiService;
    private final TaskManager taskManager;
    protected final String symbol;
    protected final Map<Double, Double> bids = new ConcurrentHashMap<>();
    protected final Map<Double, Double> asks = new ConcurrentHashMap<>();
    protected final TreeMap<Long, JSONObject> initializationMessagesQueue = new TreeMap<>();
    protected OrderBookCallback callback;
    protected ReadyStateCallback readyCallback;

    private long depthEndpointLockTimeMills = -1;
    protected long orderBookLastUpdateId = -1;
    protected boolean isOrderBookInitialized = false;

    public OrderBookHandler(String symbol,
                            APIService apiService,
                            TaskManager taskManager) {
        this.apiService = apiService;
        this.taskManager = taskManager;
        this.symbol = symbol;
        log.info("OrderBookHandler initialized");
    }

    public void scheduleTasks() {
        log.info("Setting up Order Book update task...");
        this.taskManager.scheduleAtFixedRate(LOG_ORDER_BOOK_MESSAGES_TASK_KEY, this::logOrderBookUpdate, 0, 100, TimeUnit.MILLISECONDS);
        log.info("Order Book update task scheduled");
    }

    public void onMessage(JSONObject message) {
        long updateId = message.getLong("u");
        log.debug(String.format("Received order book message: %s", message));
        if (!isOrderBookInitialized) {
            log.info("Order book not initialized. Queuing message for initialization...");
            initializeOrderBook(updateId, message);
            return;
        }

        if (message.getLong("pu") == orderBookLastUpdateId) {
            log.debug(String.format("Valid order book update received (updateId: %d).", updateId));
            updateOrderBook(updateId, message);
        } else {
            log.warn("Order book out of sync. Reinitialization required.");
            isOrderBookInitialized = false;
            readyCallback.notifyReadyStateUpdate(false);
        }
    }

    private void initializeOrderBook(long updateId, JSONObject message) {
        log.info("Initializing order book...");
        if (depthEndpointLockTimeMills != -1 &&
                System.currentTimeMillis() < depthEndpointLockTimeMills + DEPTH_UNLOCK_WAIT_TIME_MILLS) {
            log.info("Waiting for '/depth' endpoint to unlock...");
            return;
        }

        initializationMessagesQueue.put(updateId, message);
        log.info(String.format("Message added to initialization queue (size: %d).", initializationMessagesQueue.size()));

        OrderBook snapshot = null;
        if (initializationMessagesQueue.size() < 6) {
            snapshot = apiService.getOrderBookPublicAPI(symbol).getResponse();
        } else if (initializationMessagesQueue.size() > 11) {
            initializationMessagesQueue.clear();
            log.warn("Initialization queue exceeded maximum size. Clearing and retrying...");
            return;
        }
        if (snapshot == null) {
            return;
        }

        log.info("Applying snapshot and queued updates...");
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
            log.info(String.format("Found starting point for updates: %s", startingPoint.get()));
            initializationMessagesQueue.entrySet().stream()
                    .filter(entry -> entry.getKey() >= orderBookLastUpdateId)
                    .forEach(entry -> updateOrderBook(entry.getKey(), entry.getValue()));
            isOrderBookInitialized = true;
            readyCallback.notifyReadyStateUpdate(true);
            log.info("Order book successfully initialized.");
        }
    }

    private void updateOrderBook(long updateId, JSONObject data) {
        updateOrderBook(data.getJSONArray("a"), asks);
        updateOrderBook(data.getJSONArray("b"), bids);
        orderBookLastUpdateId = updateId;

        if (isOrderBookInitialized && callback != null) {
            callback.notifyOrderBookUpdate(asks, bids);
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

    public void cancelTasks() {
        log.info("Cancelling Order Book update task...");
        taskManager.cancel(LOG_ORDER_BOOK_MESSAGES_TASK_KEY);
        log.info("Order Book update task cancelled...");
    }

    public void setCallback(OrderBookCallback callback) {
        this.callback = callback;
        log.info(String.format("Callback set: %s", callback.getClass().getName()));
    }

    public void setReadyCallback(ReadyStateCallback readyCallback) {
        this.readyCallback = readyCallback;
    }

    private void logOrderBookUpdate() {
        Map<Double, Double> asksTmpMap = new TreeMap<>(asks);
        Map<Double, Double> bidsTmpMap = new TreeMap<>(bids).descendingMap();

        asksTmpMap = asksTmpMap.entrySet().stream().limit(5).collect(LinkedHashMap::new,
                (map, entry) -> map.put(entry.getKey(), entry.getValue()),
                Map::putAll);
        bidsTmpMap = bidsTmpMap.entrySet().stream().limit(5).collect(LinkedHashMap::new,
                (map, entry) -> map.put(entry.getKey(), entry.getValue()),
                Map::putAll);

//        Log.removeLines(2, Log.ORDER_BOOK_LOGS_PATH);
        log.debug("Top 5 Asks (shorts): " + asksTmpMap);
        log.debug("Top 5 Bids (longs): " + bidsTmpMap);
    }

    public void logAll() {
        log.debug(String.format("""
                        symbol: %s
                        callback: %s
                        readyCallback: %s
                        bids: %s
                        asks: %s
                        depthEndpointLockTimeMills: %d
                        orderBookLastUpdateId: %s
                        isOrderBookInitialized: %s
                        initializationMessagesQueue: %s
                        """,
                symbol,
                callback,
                readyCallback,
                bids,
                asks,
                depthEndpointLockTimeMills,
                orderBookLastUpdateId,
                isOrderBookInitialized,
                initializationMessagesQueue
        ));
    }
}