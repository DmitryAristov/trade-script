package org.tradebot.domain;

import org.json.JSONObject;
import org.tradebot.service.ImbalanceService;
import org.tradebot.service.Strategy;

import java.util.Map;

public class TradingBotState {
    private ImbalanceService.State imbalanceState;
    private Strategy.State strategyState;
    private boolean webSocketState;
    private boolean orderBookReady;
    private boolean streamsConnected;
    private Double lastPrice;
    private Map<Double, Double> asks;
    private Map<Double, Double> bids;
    private long currentTime;

    public ImbalanceService.State getImbalanceState() {
        return imbalanceState;
    }

    public void setImbalanceState(ImbalanceService.State imbalanceState) {
        this.imbalanceState = imbalanceState;
    }

    public Strategy.State getStrategyState() {
        return strategyState;
    }

    public void setStrategyState(Strategy.State strategyState) {
        this.strategyState = strategyState;
    }

    public boolean isWebSocketState() {
        return webSocketState;
    }

    public void setWebSocketState(boolean webSocketState) {
        this.webSocketState = webSocketState;
    }

    public boolean isOrderBookReady() {
        return orderBookReady;
    }

    public void setOrderBookReady(boolean orderBookReady) {
        this.orderBookReady = orderBookReady;
    }

    public boolean isStreamsConnected() {
        return streamsConnected;
    }

    public void setStreamsConnected(boolean streamsConnected) {
        this.streamsConnected = streamsConnected;
    }

    public Double getLastPrice() {
        return lastPrice;
    }

    public void setLastPrice(Double lastPrice) {
        this.lastPrice = lastPrice;
    }

    public Map<Double, Double> getBids() {
        return bids;
    }

    public void setBids(Map<Double, Double> bids) {
        this.bids = bids;
    }

    public Map<Double, Double> getAsks() {
        return asks;
    }

    public void setAsks(Map<Double, Double> asks) {
        this.asks = asks;
    }

    public long getCurrentTime() {
        return currentTime;
    }

    public void setCurrentTime(long currentTime) {
        this.currentTime = currentTime;
    }

    @Override
    public String toString() {
        return new JSONObject(this).toString(4);
    }
}
