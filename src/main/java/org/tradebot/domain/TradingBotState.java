package org.tradebot.domain;

import org.json.JSONObject;
import org.tradebot.service.ImbalanceService;
import org.tradebot.service.OrderManager;

import java.util.Map;

public class TradingBotState {
    private ImbalanceService.State imbalanceState;
    private OrderManager.State positionState;
    private boolean marketDataWebSocketState;
    private boolean userStream;
    private boolean shouldUseOrderBook;
    private boolean orderBookReady;
    private boolean readyAccountState;
    private long countOfWorkingAccounts;
    private Double lastPrice;
    private Map<Double, Double> asks;
    private Map<Double, Double> bids;
    private String currentTime;


    public ImbalanceService.State getImbalanceState() {
        return imbalanceState;
    }

    public TradingBotState setImbalanceState(ImbalanceService.State imbalanceState) {
        this.imbalanceState = imbalanceState;
        return this;
    }

    public OrderManager.State getPositionState() {
        return positionState;
    }

    public TradingBotState setPositionState(OrderManager.State state) {
        this.positionState = state;
        return this;
    }

    public boolean isMarketDataWebSocketState() {
        return marketDataWebSocketState;
    }

    public TradingBotState setMarketDataWebSocketState(boolean marketDataWebSocketState) {
        this.marketDataWebSocketState = marketDataWebSocketState;
        return this;
    }

    public boolean isOrderBookReady() {
        return orderBookReady;
    }

    public TradingBotState setOrderBookReady(boolean orderBookReady) {
        this.orderBookReady = orderBookReady;
        return this;
    }

    public boolean isUserStream() {
        return userStream;
    }

    public TradingBotState setUserStream(boolean userStream) {
        this.userStream = userStream;
        return this;
    }

    public boolean isShouldUseOrderBook() {
        return shouldUseOrderBook;
    }

    public TradingBotState setShouldUseOrderBook(boolean shouldUseOrderBook) {
        this.shouldUseOrderBook = shouldUseOrderBook;
        return this;
    }

    public boolean isReadyAccountState() {
        return readyAccountState;
    }

    public TradingBotState setReadyAccountState(boolean readyAccountState) {
        this.readyAccountState = readyAccountState;
        return this;
    }

    public long getCountOfWorkingAccounts() {
        return countOfWorkingAccounts;
    }

    public TradingBotState setCountOfWorkingAccounts(long countOfWorkingAccounts) {
        this.countOfWorkingAccounts = countOfWorkingAccounts;
        return this;
    }

    public Double getLastPrice() {
        return lastPrice;
    }

    public TradingBotState setLastPrice(Double lastPrice) {
        this.lastPrice = lastPrice;
        return this;
    }

    public Map<Double, Double> getBids() {
        return bids;
    }

    public TradingBotState setBids(Map<Double, Double> bids) {
        this.bids = bids;
        return this;
    }

    public Map<Double, Double> getAsks() {
        return asks;
    }

    public TradingBotState setAsks(Map<Double, Double> asks) {
        this.asks = asks;
        return this;
    }

    public String getCurrentTime() {
        return currentTime;
    }

    public TradingBotState setCurrentTime(String currentTime) {
        this.currentTime = currentTime;
        return this;
    }

    @Override
    public String toString() {
        return new JSONObject(this).toString(4);
    }
}
