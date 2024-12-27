package org.tradebot.binance;

import org.json.JSONObject;
import org.tradebot.domain.MarketEntry;
import org.tradebot.listener.MarketDataListener;
import org.tradebot.service.ImbalanceService;
import org.tradebot.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TradeHandler {

    private static final int MAX_TRADE_QUEUE_SIZE = 100000;
    private final List<MarketDataListener> listeners = new ArrayList<>();

    private final ImbalanceService imbalanceService = new ImbalanceService();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    private Deque<JSONObject> activeQueue = new ArrayDeque<>(MAX_TRADE_QUEUE_SIZE);
    private Deque<JSONObject> processingQueue = new ArrayDeque<>(MAX_TRADE_QUEUE_SIZE);
    private Double lastPrice = null;
    private int maxQueueSize = 0;

    public void start() {
        scheduler.scheduleAtFixedRate(this::calculateMarketData, 1000 - System.currentTimeMillis() % 1000, 100, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        scheduler.shutdown();
    }

    public void onMessage(JSONObject message) {
        synchronized (activeQueue) {
            if (activeQueue.size() >= MAX_TRADE_QUEUE_SIZE) {
                activeQueue.pollFirst();
            }
            activeQueue.offerLast(message);
        }
    }

    private void calculateMarketData() {
        try {
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

            listeners.forEach(listener -> listener.notify(openTime, entry));
        } catch (Exception e) {
            Log.debug(e);
        }
    }

    private MarketEntry processEntry(double minPrice, double maxPrice, double volume) {
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

    public void subscribe(MarketDataListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void unsubscribe(MarketDataListener listener) {
        listeners.remove(listener);
    }
}
