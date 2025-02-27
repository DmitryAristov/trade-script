package org.tradebot.service;

import org.tradebot.binance.MarketDataWebSocketService;
import org.tradebot.binance.OrderBookHandler;
import org.tradebot.binance.PublicAPIService;
import org.tradebot.binance.TradeHandler;
import org.tradebot.domain.TradingAccountSettings;
import org.tradebot.domain.Precision;
import org.tradebot.domain.TradingAccount;
import org.tradebot.domain.TradingBotState;
import org.tradebot.util.Log;
import org.tradebot.util.TimeFormatter;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.tradebot.util.Settings.*;

public class TradingBot {
    private final Log log = new Log();

    private final Map<Integer, TradingAccountSettings> accounts = Map.of(
            0, new TradingAccountSettings(
                    "****",
                    "****",
                    "BNFCR",
                    false)
    );

    private final Map<Integer, TradingAccountSettings> testAccounts = Map.of(
            0, new TradingAccountSettings(
                    "****",
                    "****",
                    "USDT",
                    false)
//            ,
//            1, new TradingAccountSettings(
//                    "****",
//                    "****",
//                    "USDT",
//                    true)
    );

    private final Precision precision;
    private final PublicAPIService publicAPIService;
    private final TaskManager taskManager;
    private final ImbalanceService imbalanceService;
    private final VolatilityService volatilityService;
    private final MarketDataWebSocketService marketDataWebSocket;
    private final TradeHandler tradeHandler;
    private final OrderBookHandler orderBookHandler;
    private final TradingManager tradingManager;

    private static TradingBot instance;

    public static TradingBot getInstance() {
        if (instance == null) {
            instance = new TradingBot();
        }
        return instance;
    }

    private TradingBot() {
        log.info(String.format("Creating '%s' bot with %d leverage", SYMBOL, LEVERAGE));

        publicAPIService = PublicAPIService.getInstance();
        precision = publicAPIService.fetchSymbolPrecision(SYMBOL).getResponse();

        taskManager = TaskManager.getInstance();
        imbalanceService = ImbalanceService.getInstance();
        tradeHandler = TradeHandler.getInstance();
        orderBookHandler = OrderBookHandler.getInstance();
        marketDataWebSocket = MarketDataWebSocketService.getInstance();
        volatilityService = VolatilityService.getInstance();
        tradingManager = TradingManager.getInstance();

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
        tradeHandler.setCallback(imbalanceService);
        volatilityService.setCallback(imbalanceService);
        orderBookHandler.setInitializationStateCallback(marketDataWebSocket);

        if (TEST_RUN) {
            testAccounts.forEach(tradingManager::addAccount);
        } else {
            accounts.forEach(tradingManager::addAccount);
        }

        marketDataWebSocket.connect();

        log.info(String.format("'%s' bot started", SYMBOL));
        taskManager.scheduleAtFixedRate(STATE_UPDATE_TASK_KEY, this::updateBotState, 5, 1, TimeUnit.SECONDS);
        setShutdownHook();
        log.info("Shutdown hook added.");
    }

    private void setShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown hook triggered.");
            try {
                TradingBot.getInstance().logAll();
                TradingBot.getInstance().tradingManager.stopAll();
                TradingBot.getInstance().marketDataWebSocket.close();
                TradingBot.getInstance().taskManager.cancelAll();
            } catch (Exception e) {
                log.error("Failed to stop bot normally", e);
            }
            log.info("Shutdown Java...");
        }));
    }

    public Precision getPrecision() {
        return precision;
    }

    private void updateBotState() {
        tradingManager.getAccounts().forEach((_, account) -> {
            TradingBotState state = new TradingBotState()
                    .setImbalanceState(instance.imbalanceService.currentState.get())
                    .setMarketDataWebSocketState(instance.marketDataWebSocket.getReady())
                    .setLastPrice(tradeHandler.getLastPrice())
                    .setCurrentTime(TimeFormatter.now())
                    .setCountOfWorkingAccounts(tradingManager.getAccounts().values().stream().filter(TradingAccount::isReady).count())
                    .setShouldUseOrderBook(USE_ORDER_BOOK);
            if (USE_ORDER_BOOK) {
                state = state.setOrderBookReady(instance.marketDataWebSocket.getOrderBookReady())
                        .setAsks(instance.orderBookHandler.getAsks(3))
                        .setBids(instance.orderBookHandler.getBids(3));
            }
            account.updateState(state);
        });
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
            if (instance.marketDataWebSocket != null)
                instance.marketDataWebSocket.logAll();
            instance.taskManager.logAll();
            if (instance.tradingManager != null)
                instance.tradingManager.logAll();
            if (instance.publicAPIService != null) {
                instance.publicAPIService.logAll();
            }
        }
    }
}
