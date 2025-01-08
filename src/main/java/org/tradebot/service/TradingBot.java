package org.tradebot.service;

import org.tradebot.binance.RestAPIService;
import org.tradebot.binance.OrderBookHandler;
import org.tradebot.binance.UserDataHandler;
import org.tradebot.binance.WebSocketService;
import org.tradebot.binance.HttpClientService;
import org.tradebot.binance.TradeHandler;
import org.tradebot.domain.AccountInfo;
import org.tradebot.domain.Precision;
import org.tradebot.util.Log;
import org.tradebot.util.TaskManager;

public class TradingBot {

    public final int leverage;
    public final String symbol;
    public static Precision precision;
    protected static TradingBot instance;

    public static TradingBot createBot(String symbol, int leverage) {
        if (instance == null) {
            instance = new TradingBot(symbol, leverage);
        }
        return instance;
    }

    public static TradingBot getInstance() {
        return instance;
    }

    protected TradingBot(String symbol, int leverage) {
        this.symbol = symbol;
        this.leverage = leverage;
        Log.info(String.format("'%s' bot created with leverage :: %d", symbol, leverage));
        precision = apiService.fetchSymbolPrecision(symbol);
        Log.info("precision :: " + precision);
    }

    protected final HttpClientService httpClient = new HttpClientService();
    protected final RestAPIService apiService = new RestAPIService(httpClient);
    protected final TaskManager taskManager = new TaskManager();
    protected ImbalanceService imbalanceService;
    protected Strategy strategy;
    protected VolatilityService volatilityService;
    protected WebSocketService webSocketService;
    protected TradeHandler tradeHandler;
    protected OrderBookHandler orderBookHandler;
    protected UserDataHandler userDataStreamHandler;

    public void start() {
        AccountInfo accountInfo = apiService.getAccountInfo();
        if (!accountInfo.canTrade()) {
            throw new RuntimeException("account cannot trade");
        }

        double balance = accountInfo.availableBalance();
        if (balance <= 0) {
            throw new RuntimeException("no available balance to trade");
        }

        apiService.setLeverage(symbol, leverage);
        if (apiService.getLeverage(symbol) != leverage) {
            throw new RuntimeException("leverage is incorrect");
        }

        imbalanceService = new ImbalanceService();
        tradeHandler = new TradeHandler(taskManager);
        userDataStreamHandler = new UserDataHandler();
        orderBookHandler = new OrderBookHandler(symbol, apiService, taskManager);
        webSocketService = new WebSocketService(symbol, tradeHandler, orderBookHandler, userDataStreamHandler, apiService, taskManager);
        volatilityService = new VolatilityService(symbol, apiService, taskManager);
        strategy = new Strategy(symbol, leverage, apiService, taskManager);

        imbalanceService.setCallback(strategy);
        tradeHandler.setCallback(imbalanceService);
        volatilityService.setCallback(imbalanceService);
        orderBookHandler.setCallback(strategy);
        userDataStreamHandler.setCallback(strategy);
        webSocketService.setCallback(strategy);
        webSocketService.connect();

        Log.info("bot started");
    }

    public static void exit(String message) {
        Log.info("exiting from bot :: '" + message + "'");

        try {
            TradingBot.logAll();
            TradingBot.getInstance().webSocketService.stop();
            TradingBot.getInstance().taskManager.stopAll();
        } catch (Exception e) {
            Log.warn(e);
        }

        System.exit(0);
    }

    public static void logAll() {
        if (instance != null) {
            if (instance.imbalanceService != null)
                instance.imbalanceService.logAll();
            if (instance.volatilityService != null)
                instance.volatilityService.logAll();
            if (instance.tradeHandler != null)
                instance.tradeHandler.logAll();
            if (instance.orderBookHandler != null)
                instance.orderBookHandler.logAll();
            if (instance.userDataStreamHandler != null)
                instance.userDataStreamHandler.logAll();
            if (instance.strategy != null)
                instance.strategy.logAll();
            if (instance.webSocketService != null)
                instance.webSocketService.logAll();
            instance.taskManager.logAll();
        }
    }
}
