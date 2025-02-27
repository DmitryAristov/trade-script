package org.tradebot.binance;

import org.json.JSONObject;
import org.tradebot.domain.MarketEntry;
import org.tradebot.listener.MarketDataCallback;
import org.tradebot.service.TaskManager;
import org.tradebot.util.Log;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.tradebot.util.Settings.*;

public class TradeHandler {

    private final Log log = new Log("market_data/");

    private final TaskManager taskManager;
    protected MarketDataCallback callback;

    protected Deque<JSONObject> activeQueue = new ArrayDeque<>(MAX_TRADE_QUEUE_SIZE);
    protected Deque<JSONObject> processingQueue = new ArrayDeque<>(MAX_TRADE_QUEUE_SIZE);
    protected Double lastPrice = null;

    private static TradeHandler instance;

    public static TradeHandler getInstance() {
        if (instance == null) {
            instance = new TradeHandler();
        }
        return instance;
    }

    private TradeHandler() {
        this.taskManager = TaskManager.getInstance();
        log.info("TradeHandler initialized");
    }

    public void scheduleTasks() {
        this.taskManager.scheduleAtFixedRate(MARKET_DATA_UPDATE_TASK_KEY, this::updateMarketPrice,
                1000 - System.currentTimeMillis() % 1000, 100, TimeUnit.MILLISECONDS);
    }

    public void onMessage(JSONObject message) {
        synchronized (activeQueue) {
            if (activeQueue.size() >= MAX_TRADE_QUEUE_SIZE) {
                activeQueue.pollFirst();
            }
            activeQueue.offerLast(message);
        }
    }

    protected void updateMarketPrice() {
        long openTime = System.currentTimeMillis();
        Deque<JSONObject> tempQueue;
        synchronized (activeQueue) {
            tempQueue = activeQueue;
            activeQueue = processingQueue;
            processingQueue = tempQueue;
        }

        double minPrice = Double.MAX_VALUE;
        double maxPrice = Double.MIN_VALUE;
        double volume = 0;

        for (JSONObject trade : processingQueue) {
            double price = Double.parseDouble(trade.getString("p"));
            if (price > 0) {
                minPrice = Math.min(minPrice, price);
                maxPrice = Math.max(maxPrice, price);
                volume += Double.parseDouble(trade.getString("q"));
            }
        }
        processingQueue.clear();

        MarketEntry entry = processEntry(minPrice, maxPrice, volume);
        if (entry == null) {
            log.debug("empty entry...");
            return;
        }
//        log.removeLines(1);
        log.debug("entry :: " + entry);
        if (callback != null)
            callback.notifyNewMarketEntry(openTime, entry);
    }

    protected MarketEntry processEntry(double minPrice, double maxPrice, double volume) {
        MarketEntry entry = null;
        if (minPrice == Double.MAX_VALUE || maxPrice == Double.MIN_VALUE) {
            if (lastPrice != null) {
                entry = new MarketEntry(lastPrice, lastPrice, 0.0);
            }
        } else {
            entry = new MarketEntry(maxPrice, minPrice, volume);
            lastPrice = entry.average();
        }
        return entry;
    }

    public void cancelTasks() {
        taskManager.cancel(MARKET_DATA_UPDATE_TASK_KEY);
    }

    public void setCallback(MarketDataCallback callback) {
        this.callback = callback;
        log.info(String.format("Callback set: %s", callback.getClass().getName()));
    }

    public Double getLastPrice() {
        return lastPrice;
    }

    public void logAll() {
        try {
            Deque<JSONObject> snapshotActiveQueue;
            synchronized (activeQueue) {
                snapshotActiveQueue = new ArrayDeque<>(activeQueue);
            }
            Deque<JSONObject> snapshotProcessingQueue;
            synchronized (processingQueue) {
                snapshotProcessingQueue = new ArrayDeque<>(processingQueue);
            }

            log.debug(String.format("""
                            callback: %s
                            activeQueue: %s
                            processingQueue: %s
                            lastPrice: %s
                            """,
                    callback,
                    snapshotActiveQueue,
                    snapshotProcessingQueue,
                    lastPrice
            ));
        } catch (Exception e) {
            log.warn("Failed to write", e);
        }
    }
}
