package org.tradebot.service;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.tradebot.binance.APIService;
import org.tradebot.domain.Imbalance;
import org.tradebot.domain.Order;
import org.tradebot.domain.Position;
import org.tradebot.listener.ImbalanceStateCallback;
import org.tradebot.listener.OrderBookCallback;
import org.tradebot.listener.UserDataCallback;
import org.tradebot.listener.WebSocketCallback;
import org.tradebot.service.strategy_state_handlers.StrategyStateDispatcher;
import org.tradebot.util.Log;

import java.util.Collections;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.tradebot.service.TradingBot.TEST_RUN;

public class Strategy implements OrderBookCallback, ImbalanceStateCallback, UserDataCallback, WebSocketCallback {

    public static final String CHECK_ORDERS_API_MODE_TASK_KEY = "check_orders_api_task";
    private final Log log = new Log();

    private final String symbol;
    private final int leverage;

    private final APIService apiService;
    private final TaskManager taskManager;
    private StrategyStateDispatcher stateDispatcher;
    private OrderManager orderManager;

    private Map<Double, Double> bids = new ConcurrentSkipListMap<>(Collections.reverseOrder());
    private Map<Double, Double> asks = new ConcurrentSkipListMap<>();
    protected final AtomicBoolean webSocketReady = new AtomicBoolean(false);

    public enum State {
        POSITION_EMPTY,
        OPEN_ORDER_PLACED,
        POSITION_OPENED,
        CLOSING_ORDERS_CREATED
    }

    public Strategy(APIService apiService,
                    TaskManager taskManager,
                    String symbol,
                    int leverage) {
        this.symbol = symbol;
        this.leverage = leverage;
        this.apiService = apiService;
        this.taskManager = taskManager;
    }

    @Override
    public void notifyImbalanceStateUpdate(long currentTime, ImbalanceService.State imbalanceState, Imbalance imbalance) {
        log.info(String.format("Received imbalance state update: %s", imbalanceState), currentTime);
        switch (imbalanceState) {
            case PROGRESS -> {
                if (taskManager.getTaskPeriod(CHECK_ORDERS_API_MODE_TASK_KEY) != 1) {
                    log.info("Switching to API-based order checks period to 1 second...");
                    taskManager.scheduleAtFixedRate(CHECK_ORDERS_API_MODE_TASK_KEY, this::checkOrdersAPI, 0, 1, TimeUnit.SECONDS);
                }
            }
            case POTENTIAL_END_POINT -> {
                if (webSocketReady.get()) {
                    orderManager.placeOpenOrder(imbalance, getPrice(imbalance));
                }
            }
            default -> {
                if (taskManager.getTaskPeriod(CHECK_ORDERS_API_MODE_TASK_KEY) != 10) {
                    log.info("Changing API-based checks period to 10 seconds...");
                    taskManager.scheduleAtFixedRate(CHECK_ORDERS_API_MODE_TASK_KEY, this::checkOrdersAPI, 0, 10, TimeUnit.SECONDS);
                }
            }
        }
    }

    private double getPrice(@NotNull Imbalance imbalance) {
        double price = switch (imbalance.getType()) {
            case UP -> Collections.min(asks.keySet());
            case DOWN -> Collections.max(bids.keySet());
        };
        log.info(String.format("Calculated price from order book: %.2f", price));
        return price;
    }

    @Override
    public void notifyWebsocketStateChanged(boolean ready) {
        if (ready) {
            log.info("Switching to WebSocket mode...");
            if (orderManager.getState() == State.POSITION_EMPTY) {
                if (taskManager.getTaskPeriod(CHECK_ORDERS_API_MODE_TASK_KEY) != 10) {
                    log.info("Changing API-based checks period to 10 seconds...");
                    taskManager.scheduleAtFixedRate(CHECK_ORDERS_API_MODE_TASK_KEY, this::checkOrdersAPI, 0, 10, TimeUnit.SECONDS);
                }
            }
            webSocketReady.set(true);
        } else {
            webSocketReady.set(false);
            if (taskManager.getTaskPeriod(CHECK_ORDERS_API_MODE_TASK_KEY) != 1) {
                log.warn("Switching to API-based order checks period to 1 second...");
                taskManager.scheduleAtFixedRate(CHECK_ORDERS_API_MODE_TASK_KEY, this::checkOrdersAPI, 0, 1, TimeUnit.SECONDS);
            }
        }
    }

    protected void checkOrdersAPI() {
        log.debug("Sending API requests for position and orders...");
        synchronized (orderManager.getLock()) {
            try {
                CompletableFuture<Position> positionFuture = CompletableFuture.supplyAsync(() ->
                        apiService.getOpenPosition(symbol).getResponse());
                CompletableFuture<List<Order>> openedOrdersFuture = CompletableFuture.supplyAsync(() ->
                        apiService.getOpenOrders(symbol).getResponse());
                Position position = positionFuture.get();
                List<Order> openedOrders = openedOrdersFuture.get();
                log.debug("Received API responses. Dispatching state update...");
                stateDispatcher.dispatch(position, openedOrders);
            } catch (Exception e) {
                log.error("Failed to retrieve data via API", e);
            }
        }
    }

    private int userMessagesCount = 0;
    @Override
    public void notifyOrderUpdate(String clientId, String status) {
        log.info(String.format("Received order update: %s - %s", clientId, status));
//        if (!webSocketReady.get()) {
//            log.warn("WebSocket is not ready, skipping order update...");
//            return;
//        }
        if ("FILLED".equals(status)) {
//            if (TEST_RUN) {
//                userMessagesCount++;
//                if (userMessagesCount % 11 == 0) {
//                    log.info("Simulating missing order update for order: " + clientId);
//                    return;
//                }
//            }
            orderManager.handleOrderUpdate(clientId);
        }
    }

    @Override
    public void notifyPositionUpdate(@Nullable Position position) {
        log.info(String.format("Position updated: %s", position));
//        if (!webSocketReady.get()) {
//            log.warn("WebSocket is not ready, skipping account update...");
//            return;
//        }
//        if (TEST_RUN) {
//            userMessagesCount++;
//            if (userMessagesCount % 11 == 0) {
//                log.info("Simulating missing position update: " + position);
//                return;
//            }
//        }
        orderManager.handlePositionUpdate(position);
    }

    @Override
    public void notifyOrderBookUpdate(Map<Double, Double> asks, Map<Double, Double> bids) {
        this.asks = asks;
        this.bids = bids;
    }

    public void setOrderManager(OrderManager orderManager) {
        this.orderManager = orderManager;
    }

    public void setStateDispatcher(StrategyStateDispatcher stateDispatcher) {
        this.stateDispatcher = stateDispatcher;
    }

    public void logAll() {
        log.debug(String.format("""
                        Strategy state:
                            Symbol: %s
                            Leverage: %d
                            WebSocket state: %s
                        """,
                symbol, leverage, webSocketReady.get()));
    }
}
