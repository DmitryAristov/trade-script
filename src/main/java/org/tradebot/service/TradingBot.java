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

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public class TradingBot {
    private final Log log = new Log();
    public static final boolean TEST_RUN = false;

    //account settings
    public static final Precision DEFAULT_PRECISION = new Precision(1, 2);
    public static final double RISK_LEVEL;
    public static final String BASE_ASSET;

    // strategy params
    public static final double[] TAKE_PROFIT_THRESHOLDS = new double[]{0.5, 0.75};
    public static final double STOP_LOSS_MULTIPLIER = 0.02;
    public static final long POSITION_LIVE_TIME = 240; //minutes

    // imbalance service params
    public static final long DATA_LIVE_TIME = 10 * 60_000L;
    public static final long LARGE_DATA_LIVE_TIME = 60 * 60_000L;
    public static final long LARGE_DATA_ENTRY_SIZE = 15_000L;

    public static final double COMPLETE_TIME_MODIFICATOR = 0.5;
    public static final double POTENTIAL_COMPLETE_TIME_MODIFICATOR = 0.05;
    public static final double SPEED_MODIFICATOR = 1E-7;
    public static final double PRICE_MODIFICATOR = 0.02;
    public static final double MAX_VALID_IMBALANCE_PART = 0.2;

    public static final long MIN_IMBALANCE_TIME_DURATION = 10_000L;
    public static final long TIME_CHECK_CONTR_IMBALANCE = 60 * 60_000L;
    public static final long MIN_POTENTIAL_COMPLETE_TIME = 2_000L;
    public static final long MIN_COMPLETE_TIME = 60_000L;
    public static final double RETURNED_PRICE_IMBALANCE_PARTITION = 0.5;

    //volatility service params
    public static final long UPDATE_TIME_PERIOD_HOURS = 12;
    public static final int VOLATILITY_CALCULATE_PAST_TIME_DAYS = 1;
    public static final int AVERAGE_PRICE_CALCULATE_PAST_TIME_DAYS = 1;

    public static final String WS_URL;
    static {
        if (TEST_RUN) {
            WS_URL = "wss://stream.binancefuture.com/ws";
            BASE_ASSET = "USDT";
            RISK_LEVEL = 0.2;
        } else {
            WS_URL = "wss://fstream.binance.com/ws";
            BASE_ASSET = "BNFCR";
            RISK_LEVEL = 0.95;
        }
    }

    private final int leverage;
    private final String symbol;
    private final Precision precision;
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
        this.leverage = TEST_RUN ? 1 : leverage;

        precision = apiService.fetchSymbolPrecision(symbol).getResponse();

        log.info(String.format("""
                     Strategy parameters:
                        Take profit thresholds: %s
                        Stop loss multiplier: %.2f
                        Position live time: %d hours
                     
                     ImbalanceService parameters:
                        complete time modificator :: %.3f
                        potential complete time modificator :: %.3f
                        speed modificator :: %s
                        price modificator :: %s
                        maximum valid imbalance part when open position :: %.3f
                        minimum imbalance time duration :: %d seconds
                        minimum potential complete time :: %d seconds
                        minimum complete time :: %d seconds
                        data live time :: %d minutes
                        large data live time :: %d minutes
                        large data entry size :: %d seconds
                        time in the past to check for contr-imbalance :: %d minutes
                        already returned price imbalance partition on potential endpoint check %.3f
                     
                     VolatilityService parameters:
                        Update time period: %d hours
                        Volatility calculation period: %d days
                        Average price calculation period: %d days
                     
                     Precision: %s""",
                Arrays.toString(TAKE_PROFIT_THRESHOLDS),
                STOP_LOSS_MULTIPLIER,
                POSITION_LIVE_TIME,
                COMPLETE_TIME_MODIFICATOR,
                POTENTIAL_COMPLETE_TIME_MODIFICATOR,
                SPEED_MODIFICATOR,
                PRICE_MODIFICATOR,
                MAX_VALID_IMBALANCE_PART,
                TimeUnit.MILLISECONDS.toSeconds(MIN_IMBALANCE_TIME_DURATION),
                TimeUnit.MILLISECONDS.toSeconds(MIN_POTENTIAL_COMPLETE_TIME),
                TimeUnit.MILLISECONDS.toSeconds(MIN_COMPLETE_TIME),
                TimeUnit.MILLISECONDS.toMinutes(DATA_LIVE_TIME),
                TimeUnit.MILLISECONDS.toMinutes(LARGE_DATA_LIVE_TIME),
                TimeUnit.MILLISECONDS.toSeconds(LARGE_DATA_ENTRY_SIZE),
                TimeUnit.MILLISECONDS.toMinutes(TIME_CHECK_CONTR_IMBALANCE),
                RETURNED_PRICE_IMBALANCE_PARTITION,
                UPDATE_TIME_PERIOD_HOURS,
                VOLATILITY_CALCULATE_PAST_TIME_DAYS,
                AVERAGE_PRICE_CALCULATE_PAST_TIME_DAYS,
                precision));
    }

    public void start() {
//        long binanceTime = apiService.getBinanceServerTime().getResponse();
//        HttpClient.TIME_DIFF = binanceTime - System.currentTimeMillis();
//        log.info("Local and Binance server time difference in mills: " + HttpClient.TIME_DIFF);

        AccountInfo accountInfo = apiService.getAccountInfo().getResponse();
        if (!accountInfo.canTrade()) {
            throw log.throwError("Account cannot trade");
        }

        double balance = accountInfo.availableBalance();
        if (balance <= 0) {
            throw log.throwError("Not enough balance to trade");
        }

        apiService.setLeverage(symbol, leverage);
        if (apiService.getLeverage(symbol).getResponse() != leverage) {
            throw log.throwError("Leverage is incorrect or was not set");
        }

        imbalanceService = new ImbalanceService();
        tradeHandler = new TradeHandler(taskManager);
        userDataStreamHandler = new UserDataHandler(symbol);
        orderBookHandler = new OrderBookHandler(symbol, apiService);
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
        orderBookHandler.setReadyCallback(webSocketService);
        userDataStreamHandler.setCallback(strategy);
        webSocketService.setCallback(strategy);
        webSocketService.connect();

        log.info(String.format("'%s' bot started", symbol));
        setShutdownHook();
        log.info("Shutdown hook added.");
    }

    private void setShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown hook triggered.");
            try {
                TradingBot.getInstance().logAll();
                TradingBot.getInstance().webSocketService.close();
                TradingBot.getInstance().taskManager.cancelAll();
            } catch (Exception e) {
                log.error("failed to stop bot normally", e);
            }
            log.info("Shutdown Java...");
        }));
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
            if (instance.orderManager != null)
                instance.orderManager.logAll();
        }
    }
}
