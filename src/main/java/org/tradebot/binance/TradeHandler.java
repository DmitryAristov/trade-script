package org.tradebot.binance;

import org.json.JSONObject;
import org.tradebot.domain.MarketEntry;
import org.tradebot.listener.MarketDataCallback;
import org.tradebot.util.Log;
import org.tradebot.util.TaskManager;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;

public class TradeHandler {

    private static final int MAX_TRADE_QUEUE_SIZE = 100000;
    protected MarketDataCallback callback;

    protected Deque<JSONObject> activeQueue = new ArrayDeque<>(MAX_TRADE_QUEUE_SIZE);
    protected Deque<JSONObject> processingQueue = new ArrayDeque<>(MAX_TRADE_QUEUE_SIZE);
    protected Double lastPrice = null;

    public TradeHandler(TaskManager taskManager) {
        taskManager.create("market_data_update", this::updateMarketData, TaskManager.Type.PERIOD,
                1000 - System.currentTimeMillis() % 1000, 100, TimeUnit.MILLISECONDS);
        Log.info("service started");
    }

    public void onMessage(JSONObject message) {
        synchronized (activeQueue) {
            if (activeQueue.size() >= MAX_TRADE_QUEUE_SIZE) {
                activeQueue.pollFirst();
            }
            activeQueue.offerLast(message);
        }
    }

    protected void updateMarketData() {
        try {
            //TODO: how can we move to binance time?
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
                return;
            }
//            Log.removeLines(1, Log.MARKET_DATA_LOGS_PATH);
            Log.info("entry :: " + entry, Log.MARKET_DATA_LOGS_PATH);

            if (callback != null)
                callback.notifyNewMarketEntry(openTime, entry);
        } catch (Exception e) {
            throw Log.error(e);
        }
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

    public void setCallback(MarketDataCallback callback) {
        this.callback = callback;
        Log.info(String.format("callback added %s", callback.getClass().getName()));
    }

    public void logAll() {
        Log.debug(String.format("""
                        callback: %s
                        activeQueue: %s
                        processingQueue: %s
                        lastPrice: %s
                        """,
                callback,
                activeQueue,
                processingQueue,
                lastPrice
        ));
    }
}
