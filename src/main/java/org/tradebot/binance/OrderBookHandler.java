package org.tradebot.binance;

import org.json.JSONArray;
import org.json.JSONObject;
import org.tradebot.domain.OrderBook;
import org.tradebot.listener.OrderBookListener;
import org.tradebot.util.Log;
import org.tradebot.util.TaskManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class OrderBookHandler {
    public static final long DEPTH_UNLOCK_WAIT_TIME_MILLS = 50_000L;
    protected final List<OrderBookListener> listeners = new ArrayList<>();
    private final String symbol;

    protected final Map<Double, Double> bids = new ConcurrentHashMap<>();
    protected final Map<Double, Double> asks = new ConcurrentHashMap<>();

    private final TreeMap<Long, JSONObject> initializationMessagesQueue = new TreeMap<>();

    private final RestAPIService apiService;
    private final TaskManager taskManager;

    private long depthEndpointLockTimeMills = -1;
    protected long orderBookLastUpdateId = -1;
    protected OrderBook snapshot;
    protected boolean isOrderBookInitialized = false;

    public OrderBookHandler(String symbol,
                            RestAPIService apiService,
                            TaskManager taskManager) {
        this.symbol = symbol;
        this.apiService = apiService;
        this.taskManager = taskManager;
        this.taskManager.create("sout_order_book", this::soutOrderBook, TaskManager.Type.PERIOD, 0, 100, TimeUnit.MILLISECONDS);
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
            Log.info("/depth endpoint is locked due to rate limit");
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
        Log.info("init order book attempt with message = " + message);

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

        if (isOrderBookInitialized) {
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

    public void logAll() {
        Log.debug(String.format("symbol: %s", symbol));
        Log.debug(String.format("listeners: %s", listeners));
        Log.debug(String.format("bids: %s", bids));
        Log.debug(String.format("asks: %s", asks));
        Log.debug(String.format("depthEndpointLockTimeMills: %d", depthEndpointLockTimeMills));
        Log.debug(String.format("orderBookLastUpdateId: %s", orderBookLastUpdateId));
        Log.debug(String.format("isOrderBookInitialized: %s", isOrderBookInitialized));
    }

    private void soutOrderBook() {
        Map<Double, Double> asksTmpMap = new TreeMap<>(asks);
        Map<Double, Double> bidsTmpMap = new TreeMap<>(bids).descendingMap();

        asksTmpMap = asksTmpMap.entrySet().stream().limit(50).collect(LinkedHashMap::new,
                (map, entry) -> map.put(entry.getKey(), entry.getValue()),
                Map::putAll);
        bidsTmpMap = bidsTmpMap.entrySet().stream().limit(50).collect(LinkedHashMap::new,
                (map, entry) -> map.put(entry.getKey(), entry.getValue()),
                Map::putAll);

        Log.removeLines(2, Log.ORDER_BOOK_LOGS_PATH);
        Log.info("Shorts: " + asksTmpMap, Log.ORDER_BOOK_LOGS_PATH);
        Log.info("Longs : " + bidsTmpMap, Log.ORDER_BOOK_LOGS_PATH);
    }

    public void stop() {
        taskManager.stop("sout_order_book");
    }
}