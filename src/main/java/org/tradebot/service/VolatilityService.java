package org.tradebot.service;

import org.tradebot.binance.RestAPIService;
import org.tradebot.domain.MarketEntry;
import org.tradebot.listener.VolatilityListener;
import org.tradebot.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class VolatilityService {

    private static final long UPDATE_TIME_PERIOD_HOURS = 12;
    private static final int VOLATILITY_CALCULATE_PAST_TIME = 1;
    private static final int AVERAGE_PRICE_CALCULATE_PAST_TIME = 1;

    private final RestAPIService apiService;
    private final List<VolatilityListener> listeners = new ArrayList<>();
    private final String symbol;
    private ScheduledExecutorService volatilityUpdateScheduler;

    public VolatilityService(String symbol, RestAPIService apiService) {
        Log.info(String.format("""
                        imbalance parameters:
                            update time period :: %d hours
                            volatility calculation past time :: %d days
                            average price calculation past time :: %d days""",
                UPDATE_TIME_PERIOD_HOURS,
                VOLATILITY_CALCULATE_PAST_TIME,
                AVERAGE_PRICE_CALCULATE_PAST_TIME));
        this.symbol = symbol;
        this.apiService = apiService;
    }

    public void start() {
        if (volatilityUpdateScheduler == null || volatilityUpdateScheduler.isShutdown()) {
            volatilityUpdateScheduler = Executors.newScheduledThreadPool(1);
        }
        volatilityUpdateScheduler.scheduleAtFixedRate(this::updateParams, 0, UPDATE_TIME_PERIOD_HOURS, TimeUnit.HOURS);
        Log.info("service started");
    }

    private void updateParams() {
        double volatility = calculateVolatility();
        double average = calculateAverage();
        Log.info(String.format("volatility=%.2f, average=%.2f", volatility, average));
        listeners.forEach(listener -> listener.notifyVolatilityUpdate(volatility, average));
    }

    public void stop() {
        if (volatilityUpdateScheduler != null && !volatilityUpdateScheduler.isShutdown()) {
            volatilityUpdateScheduler.shutdownNow();
            volatilityUpdateScheduler = null;
        }
        Log.info("service stopped");
    }

    private double calculateVolatility() {
        TreeMap<Long, MarketEntry> marketData = apiService.getMarketDataPublicAPI(symbol, "15m", VOLATILITY_CALCULATE_PAST_TIME * 24 * 60 / 15);

        if (marketData.size() < 2) {
            return 0.;
        }

        List<Double> changes = new ArrayList<>();
        for (MarketEntry marketDatum : marketData.values()) {
            double priceDiff = marketDatum.high() - marketDatum.low();
            double priceAverage = marketDatum.average();
            changes.add(priceDiff / priceAverage);
        }
        return changes.stream().reduce(0., Double::sum) / changes.size();
    }

    private double calculateAverage() {
        TreeMap<Long, MarketEntry> marketData = apiService.getMarketDataPublicAPI(symbol, "15m", AVERAGE_PRICE_CALCULATE_PAST_TIME * 24 * 60 / 15);

        if (marketData.size() < 2) {
            return 0.;
        }

        double sum = 0;
        for (MarketEntry marketDatum : marketData.values()) {
            double priceAverage = marketDatum.average();
            sum += priceAverage;
        }

        return sum / marketData.size();
    }

    public void subscribe(VolatilityListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
            Log.info(String.format("listener added %s", listener.getClass().getName()));
        }
    }

    public void unsubscribe(VolatilityListener listener) {
        listeners.remove(listener);
        Log.info(String.format("listener removed %s", listener.getClass().getName()));
    }

    public void logAll() {
        Log.debug(String.format("symbol: %s", symbol));
        Log.debug(String.format("listeners: %s", listeners));
        Log.debug(String.format("volatilityUpdateScheduler isShutdown: %s", volatilityUpdateScheduler.isShutdown()));
        Log.debug(String.format("volatilityUpdateScheduler isTerminated: %s", volatilityUpdateScheduler.isTerminated()));
    }
}
