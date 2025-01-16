package org.tradebot.service;

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
import org.tradebot.util.TimeFormatter;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.tradebot.service.ImbalanceService.fakeOpenTime;
import static org.tradebot.util.OrderUtils.STOP_LOSS_MULTIPLIER;
import static org.tradebot.util.OrderUtils.TAKE_PROFIT_THRESHOLDS;

public class Strategy implements OrderBookCallback, ImbalanceStateCallback, UserDataCallback, WebSocketCallback {

    public static final long POSITION_LIVE_TIME = 5;
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
    private final AtomicBoolean websocketState = new AtomicBoolean(false);

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

        log.info(String.format("""
                        Initializing Strategy with parameters:
                            Take profit thresholds: %s
                            Stop loss multiplier: %.2f
                            Position live time: %d hours
                        """,
                Arrays.toString(TAKE_PROFIT_THRESHOLDS),
                STOP_LOSS_MULTIPLIER,
                POSITION_LIVE_TIME));
    }

    @Override
    public void notifyImbalanceStateUpdate(long currentTime, ImbalanceService.State imbalanceState, Imbalance imbalance) {
        log.info(String.format("Received imbalance state update: %s", imbalanceState), currentTime);
        if (imbalanceState == ImbalanceService.State.POTENTIAL_END_POINT) {
            if (websocketState.get()) {
                double price = getPrice(imbalance);
                orderManager.placeOpenOrder(imbalance, price);
            } else {
                fakeOpenTime = System.currentTimeMillis() + 30_000L;
                log.info("Updating next open position time to " + TimeFormatter.format(fakeOpenTime));
            }
        }
    }

    private double getPrice(Imbalance imbalance) {
        double price = switch (imbalance.getType()) {
            case UP -> Collections.min(asks.keySet());
            case DOWN -> Collections.max(bids.keySet());
        };
        log.info(String.format("Calculated price from order book: %.2f", price));
        return price;
    }

    @Override
    public void notifyWebsocketStateChanged(boolean ready) {
        log.info(String.format("WebSocket state changed. Ready: %s", ready));
        websocketState.set(ready);

        if (ready) {
            log.info("Switching to WebSocket mode. Canceling API-based checks...");
            taskManager.cancel(CHECK_ORDERS_API_MODE_TASK_KEY);
        } else {
            log.warn("Switching to API-based order checks...");
            taskManager.scheduleAtFixedRate(CHECK_ORDERS_API_MODE_TASK_KEY, this::checkOrdersAPI, 0, 1, TimeUnit.SECONDS);
        }
        // TODO после переключения обратно на режим вебсокета сначала его включить,
        //  потом через апи обновить локальные данные и стейт параллельно принимая события из вебсокета,
        //  потом отфильтровать те события, которые не попали в апи и обновлять локальные данные уже с них
    }

    protected void checkOrdersAPI() {
        log.info("Sending API requests for position and orders...");
        synchronized (orderManager.getLock()) {
            CompletableFuture<Position> positionFuture = CompletableFuture.supplyAsync(() ->
                    apiService.getOpenPosition(symbol).getSuccessResponse());
            CompletableFuture<List<Order>> openedOrdersFuture = CompletableFuture.supplyAsync(() ->
                    apiService.getOpenOrders(symbol).getSuccessResponse());
            try {
                Position position = positionFuture.get();
                List<Order> openedOrders = openedOrdersFuture.get();
                log.info("Received API responses. Dispatching state update...");
                stateDispatcher.dispatch(position, openedOrders);
            } catch (Exception e) {
                log.error("Failed to retrieve data via API", e);
            }
        }
    }

    @Override
    public void notifyOrderUpdate(String clientId, String status) {
        log.debug(String.format("Received order update: clientId: %s, status: %s", clientId, status));
        if ("FILLED".equals(status))
            orderManager.handleOrderUpdate(clientId);
    }

    @Override
    public void notifyPositionUpdate(Position position) {
        log.debug(String.format("Position updated: %s", position));
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
                            Bids: %s
                            Asks: %s
                            WebSocket state: %s
                        """,
                symbol, leverage, bids, asks, websocketState));
    }
}
