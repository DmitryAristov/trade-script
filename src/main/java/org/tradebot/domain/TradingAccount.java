package org.tradebot.domain;

import org.tradebot.binance.*;
import org.tradebot.service.ImbalanceService;
import org.tradebot.service.OrderManager;
import org.tradebot.service.Strategy;
import org.tradebot.strategy_state_handlers.*;
import org.tradebot.util.Log;
import org.tradebot.util.TimeFormatter;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.tradebot.util.Settings.*;

public class TradingAccount {
    private final Log log;

    private final HttpClient httpClient;
    private final UserWebSocketService userWebSocketService;
    private final Strategy strategy;
    private final OrderManager orderManager;
    private final int clientNumber;
    private final AtomicBoolean ready = new AtomicBoolean(false);

    public TradingAccount(int clientNumber,
                          TradingAccountSettings settings) {
        this.clientNumber = clientNumber;
        this.log = new Log(clientNumber);
        log.info("Initializing account...");

        httpClient = new HttpClient(settings.apiKey(), settings.apiSecret(), clientNumber);
        APIService apiService = new APIService(httpClient, clientNumber);

        checkAccount(apiService, settings.customLeverage());

        UserDataHandler userDataHandler = new UserDataHandler(clientNumber, settings.baseAsset());
        userWebSocketService = new UserWebSocketService(userDataHandler, httpClient, clientNumber);

        StrategyStateDispatcher stateDispatcher = new StrategyStateDispatcher(clientNumber);
        orderManager = new OrderManager(httpClient, clientNumber,
                settings.baseAsset(), settings.customLeverage(), stateDispatcher);

        stateDispatcher.registerHandler(OrderManager.State.POSITION_EMPTY,
                new EmptyPositionStateHandler(orderManager, clientNumber));
        stateDispatcher.registerHandler(OrderManager.State.OPEN_ORDER_PLACED,
                new OpenPositionOrderPlacedStateHandler(apiService, orderManager, clientNumber));
        stateDispatcher.registerHandler(OrderManager.State.OPEN_ORDER_FILLED,
                new OpenPositionOrderFilledStateHandler(orderManager, clientNumber));
        stateDispatcher.registerHandler(OrderManager.State.STOP_ORDERS_PLACED,
                new StopOrdersCreatedStateHandler(apiService, orderManager, clientNumber));
        stateDispatcher.registerHandler(OrderManager.State.FIRST_TAKE_FILLED,
                new BreakEvenStopCreatedStateHandler(apiService, orderManager, clientNumber));
        stateDispatcher.registerHandler(OrderManager.State.BREAK_EVEN_ORDER_CREATED,
                new BreakEvenStopCreatedStateHandler(apiService, orderManager, clientNumber));

        userDataHandler.setCallback(orderManager);
        strategy = new Strategy(orderManager, clientNumber);
        userWebSocketService.setCallback(strategy);

        log.info("Account successfully initialized.");
    }

    private void checkAccount(APIService apiService, boolean customLeverage) {
        AccountInfo accountInfo = apiService.getAccountInfo().getResponse();
        if (!accountInfo.canTrade()) {
            throw log.throwError("Account cannot trade");
        }

        double balance = accountInfo.availableBalance();
        if (balance <= 0) {
            throw log.throwError("Account has not enough balance to trade");
        }

        apiService.setLeverage(SYMBOL, LEVERAGE);
        if (!customLeverage && apiService.getLeverage(SYMBOL).getResponse() != LEVERAGE) {
            log.warn("Account had incorrect leverage");
        }
    }

    public void start() {
        log.info("Starting account " + clientNumber);
        ImbalanceService.getInstance().addCallback(strategy);
        OrderBookHandler.getInstance().addCallback(strategy);
        MarketDataWebSocketService.getInstance().addCallback(strategy);
        userWebSocketService.connect();
        ready.set(true);
        log.info("Started.");
    }

    public void stop() {
        log.info("Stopping account " + clientNumber);
        if (TEST_RUN)
            orderManager.closePositionAndResetState();
        ImbalanceService.getInstance().removeCallback(strategy);
        OrderBookHandler.getInstance().removeCallback(strategy);
        MarketDataWebSocketService.getInstance().removeCallback(strategy);
        userWebSocketService.close();
        ready.set(false);
        log.info("Stopped.");
    }

    public boolean isReady() {
        return ready.get();
    }

    public OrderManager getOrderManager() {
        return orderManager;
    }

    public boolean getUserWebSocketServiceReady() {
        return userWebSocketService.getReady().get();
    }

    public void logAll() {
        httpClient.logAll();
        userWebSocketService.logAll();
        orderManager.logAll();
        strategy.logAll();

        try {
            log.debug(String.format("""
                            TradingAccount State:
                            ready: %s
                            clientNumber: %d
                            """,
                    ready.get(), clientNumber));
        } catch (Exception e) {
            log.warn("Failed to write", e);
        }
    }

    public void updateState(TradingBotState state) {
        try {
            state.setPositionState(this.getOrderManager().getState())
                    .setUserStream(this.getUserWebSocketServiceReady())
                    .setReadyAccountState(this.isReady());

            log.updateState(state);
        } catch (Exception e) {
            log.error("Update state failed", e);
        }

    }
}
