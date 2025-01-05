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
        Log.info(String.format("%s bot created with leverage %d", symbol, leverage));
        precision = apiService.fetchSymbolPrecision(symbol);
        Log.info(String.format("precision: %s", precision));
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
            throw Log.error("account cannot trade");
        }

        double balance = accountInfo.availableBalance();
        if (balance <= 0) {
            throw Log.error("no available balance to trade");
        }

        apiService.setLeverage(symbol, leverage);
        if (apiService.getLeverage(symbol) != leverage) {
            throw Log.error("leverage is incorrect");
        }

        imbalanceService = new ImbalanceService();
        tradeHandler = new TradeHandler(taskManager);
        userDataStreamHandler = new UserDataHandler();
        orderBookHandler = new OrderBookHandler(symbol, apiService, taskManager);
        webSocketService = new WebSocketService(symbol, tradeHandler, orderBookHandler, userDataStreamHandler, apiService, taskManager);
        volatilityService = new VolatilityService(symbol, apiService, taskManager);
        strategy = new Strategy(symbol, leverage, apiService, taskManager);

        imbalanceService.subscribe(strategy);
        tradeHandler.subscribe(imbalanceService);
        volatilityService.subscribe(imbalanceService);
        orderBookHandler.subscribe(strategy);
        userDataStreamHandler.subscribe(strategy);
        webSocketService.subscribe(strategy);

        webSocketService.connect();
        Log.info("bot started");
    }

    public void stop() {
        imbalanceService.unsubscribe(strategy);
        volatilityService.unsubscribe(imbalanceService);
        tradeHandler.unsubscribe(imbalanceService);
        orderBookHandler.unsubscribe(strategy);
        userDataStreamHandler.unsubscribe(strategy);
        webSocketService.unsubscribe(strategy);

        taskManager.stopAll();
        webSocketService.stop();
        Log.info("bot stopped");
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
