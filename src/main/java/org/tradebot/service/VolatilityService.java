package org.tradebot.service;

import org.tradebot.binance.APIService;
import org.tradebot.domain.MarketEntry;
import org.tradebot.listener.VolatilityCallback;
import org.tradebot.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

public class VolatilityService {

    private static final long UPDATE_TIME_PERIOD_HOURS = 12;
    private static final int VOLATILITY_CALCULATE_PAST_TIME_DAYS = 1;
    private static final int AVERAGE_PRICE_CALCULATE_PAST_TIME_DAYS = 1;
    private final Log log = new Log();

    private final APIService apiService;
    private final String symbol;
    private VolatilityCallback callback;

    public VolatilityService(String symbol, APIService apiService, TaskManager taskManager) {
        this.symbol = symbol;
        this.apiService = apiService;

        log.info(String.format("""
                        Initializing VolatilityService with parameters:
                            Update time period: %d hours
                            Volatility calculation period: %d days
                            Average price calculation period: %d days""",
                UPDATE_TIME_PERIOD_HOURS,
                VOLATILITY_CALCULATE_PAST_TIME_DAYS,
                AVERAGE_PRICE_CALCULATE_PAST_TIME_DAYS));

        taskManager.scheduleAtFixedRate("volatility_update", this::updateVolatility, 0, UPDATE_TIME_PERIOD_HOURS, TimeUnit.HOURS);
        log.info("VolatilityService started successfully.");
    }

    private void updateVolatility() {
        log.info("Updating volatility and average price...");
        double volatility = calculateVolatility();
        double average = calculateAverage();
        log.info(String.format("Volatility: %.2f, Average price: %.2f", volatility, average));

        if (callback != null) {
            log.info("Notifying callback with updated values.");
            callback.notifyVolatilityUpdate(volatility, average);
        }
    }

    private double calculateVolatility() {
        log.debug("Calculating volatility...");
        TreeMap<Long, MarketEntry> marketData = fetchMarketData("15m", VOLATILITY_CALCULATE_PAST_TIME_DAYS);

        if (marketData.size() < 2) {
            log.warn("Insufficient market data for volatility calculation.");
            return 0.0;
        }

        List<Double> changes = new ArrayList<>();
        for (MarketEntry marketDatum : marketData.values()) {
            double priceDiff = marketDatum.high() - marketDatum.low();
            double priceAverage = marketDatum.average();
            changes.add(priceDiff / priceAverage);
        }

        double volatility = changes.stream().reduce(0., Double::sum) / changes.size();
        log.debug(String.format("Calculated volatility: %.2f", volatility));
        return volatility;
    }

    private double calculateAverage() {
        log.debug("Calculating average price...");
        TreeMap<Long, MarketEntry> marketData = fetchMarketData("15m", AVERAGE_PRICE_CALCULATE_PAST_TIME_DAYS);

        if (marketData.size() < 2) {
            log.warn("Insufficient market data for average price calculation.");
            return 0.0;
        }

        double sum = marketData.values().stream().mapToDouble(MarketEntry::average).sum();
        double average = sum / marketData.size();
        log.debug(String.format("Calculated average price: %.2f", average));
        return average;
    }

    private TreeMap<Long, MarketEntry> fetchMarketData(String interval, int days) {
        int requiredEntries = days * 24 * 60 / 15;
        log.debug(String.format("Fetching market data for symbol: %s, interval: %s, required entries: %d", symbol, interval, requiredEntries));
        TreeMap<Long, MarketEntry> marketData = apiService.getMarketDataPublicAPI(symbol, interval, requiredEntries).getSuccessResponse();
        log.debug(String.format("Fetched %d market entries.", marketData.size()));
        return marketData;
    }

    public void setCallback(VolatilityCallback callback) {
        this.callback = callback;
        log.info(String.format("Callback set: %s", callback.getClass().getName()));
    }

    public void logAll() {
        log.debug(String.format("""
                        VolatilityService state:
                            Symbol: %s
                            Callback: %s
                        """, symbol, callback));
    }
}
