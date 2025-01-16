package org.tradebot.service;

import org.tradebot.binance.APIService;
import org.tradebot.binance.OrderBookHandler;
import org.tradebot.binance.UserDataHandler;
import org.tradebot.binance.WebSocketService;
import org.tradebot.binance.HttpClient;
import org.tradebot.binance.TradeHandler;
import org.tradebot.domain.AccountInfo;
import org.tradebot.domain.Precision;
import org.tradebot.service.strategy_state_handlers.*;
import org.tradebot.util.Log;

public class TradingBot {
    private final Log log = new Log();
    public static final Precision DEFAULT_PRECISION = new Precision(1, 2);

    private final int leverage;
    private final String symbol;
    private final Precision precision;

    private static TradingBot instance;

    public static TradingBot getInstance() {
        return instance;
    }

    public static TradingBot createBot(String symbol, int leverage) {
        if (instance == null) {
            instance = new TradingBot(symbol, leverage);
        }
        return instance;
    }

    protected TradingBot(String symbol, int leverage) {
        log.info(String.format("Creating '%s' bot with %d leverage", symbol, leverage));
        this.symbol = symbol;
        this.leverage = leverage;
        precision = apiService.fetchSymbolPrecision(symbol).getSuccessResponse();
        log.info(String.format("'%s' bot created precision: %s", symbol, precision));
    }

    protected final HttpClient httpClient = new HttpClient();
    protected final APIService apiService = new APIService(httpClient);
    protected final TaskManager taskManager = new TaskManager();
    protected StrategyStateDispatcher stateDispatcher;
    protected OrderManager orderManager;
    protected ImbalanceService imbalanceService;
    protected Strategy strategy;
    protected VolatilityService volatilityService;
    protected WebSocketService webSocketService;
    protected TradeHandler tradeHandler;
    protected OrderBookHandler orderBookHandler;
    protected UserDataHandler userDataStreamHandler;

    public void start() {
        AccountInfo accountInfo = apiService.getAccountInfo().getSuccessResponse();
        if (!accountInfo.canTrade()) {
            throw log.throwError("Account cannot trade");
        }

        double balance = accountInfo.availableBalance();
        if (balance <= 0) {
            throw log.throwError("Not enough balance to trade");
        }

        apiService.setLeverage(symbol, leverage);
        if (apiService.getLeverage(symbol).getSuccessResponse() != leverage) {
            throw log.throwError("Leverage is incorrect or was not set");
        }

        imbalanceService = new ImbalanceService();
        tradeHandler = new TradeHandler(taskManager);
        userDataStreamHandler = new UserDataHandler(symbol);
        orderBookHandler = new OrderBookHandler(symbol, apiService, taskManager);
        webSocketService = new WebSocketService(symbol, tradeHandler, orderBookHandler, userDataStreamHandler, apiService, taskManager);
        volatilityService = new VolatilityService(symbol, apiService, taskManager);

        strategy = new Strategy(apiService, taskManager, symbol, leverage);
        orderManager = new OrderManager(taskManager, apiService, symbol, leverage);

        stateDispatcher = new StrategyStateDispatcher(orderManager);
        stateDispatcher.registerHandler(Strategy.State.POSITION_EMPTY, new EmptyPositionStateHandler(orderManager));
        stateDispatcher.registerHandler(Strategy.State.OPEN_ORDER_PLACED, new OpenPositionOrderPlacedStateHandler(apiService, orderManager, symbol));
        stateDispatcher.registerHandler(Strategy.State.POSITION_OPENED, new PositionOpenedStateHandler(apiService, orderManager, symbol));
        stateDispatcher.registerHandler(Strategy.State.CLOSING_ORDERS_CREATED, new ClosingOrdersCreatedStateHandler(apiService, orderManager, symbol));

        strategy.setOrderManager(orderManager);
        strategy.setStateDispatcher(stateDispatcher);

        imbalanceService.setCallback(strategy);
        tradeHandler.setCallback(imbalanceService);
        volatilityService.setCallback(imbalanceService);
        orderBookHandler.setCallback(strategy);
        userDataStreamHandler.setCallback(strategy);
        webSocketService.setCallback(strategy);
        webSocketService.connect();

        log.info(String.format("'%s' bot started", symbol));
    }

    public void exit(String message) {
        log.info(String.format("Exiting from bot: %s", message));

        try {
            TradingBot.getInstance().taskManager.cancelAll();
            TradingBot.getInstance().webSocketService.close();
            TradingBot.getInstance().logAll();
        } catch (Exception e) {
            log.error("failed to stop bot normally", e);
        }

        log.info("Shutdown Java...");
        System.exit(0);
    }

    public Precision getPrecision() {
        return precision;
    }

    public void logAll() {
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
