package org.tradebot.service;

import org.tradebot.binance.RestAPIService;
import org.tradebot.domain.MarketEntry;
import org.tradebot.listener.VolatilityListener;
import org.tradebot.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

public class VolatilityService {

    /**
     * Период обновления волатильности и средней цены (1000мс * 60с * 60м * 24ч = 1 день)
     */
    private static final long UPDATE_TIME_PERIOD_MILLS = 24L * 60L * 60L * 1000L;
    private static final long INTERVAL = 15;
    private static final long VOLATILITY_CALCULATE_PAST_TIME = 24 * 60 / INTERVAL;
    private static final long AVERAGE_PRICE_CALCULATE_PAST_TIME = 1;


    private final RestAPIService apiService;
    private final List<VolatilityListener> listeners = new ArrayList<>();
    private long lastUpdateTime = -1L;

    public VolatilityService(RestAPIService apiService) {
        Log.info(String.format("""
                        imbalance parameters:
                            update time period :: %d hours
                            volatility calculation past time :: %d days
                            average price calculation past time :: %d days""",
                UPDATE_TIME_PERIOD_MILLS / 3_600_000L,
                VOLATILITY_CALCULATE_PAST_TIME,
                AVERAGE_PRICE_CALCULATE_PAST_TIME));
        this.apiService = apiService;
    }

    public void onTick(long currentTime, MarketEntry currentEntry) {
        if (currentTime - lastUpdateTime > UPDATE_TIME_PERIOD_MILLS) {
            double volatility = calculateVolatility(currentTime);
            double average = calculateAverage(currentTime);
            Log.debug(String.format("volatility=%.2f%% || average=%.2f$", volatility * 100, average), currentTime);
            listeners.forEach(listener -> listener.notifyVolatilityUpdate(volatility, average));
            lastUpdateTime = currentTime;
        }
    }

    /**
     * Метод определяет волатильность актива
     */
    private double calculateVolatility(long currentTime) {
        TreeMap<Long, MarketEntry> marketData = apiService.getMarketData(INTERVAL, VOLATILITY_CALCULATE_PAST_TIME);

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

    /**
     * Метод определяет среднюю цену актива
     */
    private double calculateAverage(long currentTime) {
        TreeMap<Long, MarketEntry> marketData = apiService.getMarketData(INTERVAL, AVERAGE_PRICE_CALCULATE_PAST_TIME);

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

    public void subscribe(VolatilityListener... listeners) {
        Arrays.stream(listeners).forEach(this::subscribe);
    }

    public void subscribe(VolatilityListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void unsubscribe(VolatilityListener listener) {
        listeners.remove(listener);
    }

    public void unsubscribeAll() {
        listeners.clear();
    }
}
