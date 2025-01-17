package org.tradebot.service;

import org.tradebot.binance.APIService;
import org.tradebot.domain.*;
import org.tradebot.util.Log;
import org.tradebot.util.OrderUtils;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.tradebot.service.TradingBot.POSITION_LIVE_TIME;
import static org.tradebot.service.TradingBot.TEST_RUN;

public class OrderManager {

    public static final String CLOSE_CLIENT_ID_PREFIX = "close_";
    public static final String TIMEOUT_CLOSE_CLIENT_ID_PREFIX = "timeout_stop_";
    public static final String AUTOCLOSE_POSITION_TASK_KEY = "autoclose_position_task";

    public enum OrderType {
        OPEN, STOP, TAKE_0, TAKE_1, BREAK_EVEN, CLOSE, TIMEOUT
    }

    private final Log log = new Log();
    private final OrderUtils orderUtils = new OrderUtils();
    private final TaskManager taskManager;
    private final APIService apiService;

    private final String symbol;
    private final int leverage;
    private final Object lock = new Object();
    private final Map<OrderType, String> orders = new ConcurrentHashMap<>();
    private final AtomicReference<Strategy.State> state = new AtomicReference<>(Strategy.State.POSITION_EMPTY);
    private Imbalance currentImbalance = null;

    public OrderManager(TaskManager taskManager,
                        APIService apiService,
                        String symbol,
                        int leverage) {
        this.taskManager = taskManager;
        this.apiService = apiService;
        this.symbol = symbol;
        this.leverage = leverage;
        log.info("OrderManager initialized");
    }

    public void placeOpenOrder(final Imbalance imbalance, double price) {
        if (state.get() != Strategy.State.POSITION_EMPTY) {
            log.warn(String.format("Attempted to place open order in invalid state: %s. Action aborted.", state.get()));
            return;
        }

        currentImbalance = imbalance;
        double quantity = apiService.getAccountBalance().getResponse() * leverage / price;
        synchronized (lock) {
            log.info("Placing open order...");
            Order openOrder = apiService.placeOrder(orderUtils.createOpen(symbol, imbalance, quantity)).getResponse();
            orders.put(OrderType.OPEN, openOrder.getNewClientOrderId());
            state.set(Strategy.State.OPEN_ORDER_PLACED);
            log.info(String.format("Open order placed: %s", openOrder));
        }
    }

    public void handleOrderUpdate(String clientId) {
        if (clientId == null) {
            log.warn("Order update received without client ID. Skipping.");
            return;
        }
        synchronized (lock) {
            if (clientId.equals(orders.get(OrderType.OPEN))) {
                handleOpenOrderFilled();
            } else if (isClosingOrder(clientId)) {
                resetToEmptyPosition();
            } else if (clientId.equals(orders.get(OrderType.TAKE_0))) {
                handleFirstTakeOrderFilled();
            } else {
                log.warn(String.format("Unknown order update received: %s", clientId));
                handleUnknownFilledOrder();
            }
        }
    }

    private boolean isClosingOrder(String clientId) {
        return clientId.equals(orders.get(OrderType.CLOSE)) ||
                clientId.equals(orders.get(OrderType.STOP)) ||
                clientId.equals(orders.get(OrderType.TAKE_1)) ||
                clientId.equals(orders.get(OrderType.BREAK_EVEN)) ||
                clientId.equals(orders.get(OrderType.TIMEOUT));
    }

    public void handleOpenOrderFilled() {
        log.info("Open order filled. Transitioning to POSITION_OPENED state.");
        orders.remove(OrderType.OPEN);
        state.set(Strategy.State.POSITION_OPENED);

        Position position = apiService.getOpenPosition(symbol).getResponse();
        if (position == null) {
            log.warn("Position is empty after open order filled. Resetting state.");
            resetToEmptyPosition();
            return;
        }
        placeClosingOrdersAndScheduleCloseTask(position);
    }

    private void handleFirstTakeOrderFilled() {
        log.info("First take order filled. Placing break-even stop.");
        Position position = apiService.getOpenPosition(symbol).getResponse();
        if (position == null) {
            log.warn("Position is empty after first take order filled. Resetting state.");
            resetToEmptyPosition();
            return;
        }
        placeBreakEvenStop(position);
    }

    private void handleUnknownFilledOrder() {
        log.info("Handling unknown order update...");
        Position position = apiService.getOpenPosition(symbol).getResponse();
        if (state.get() != Strategy.State.POSITION_EMPTY && position == null) {
            log.warn("Position became empty after unknown order update or manual actions. Resetting state.");
            resetToEmptyPosition();
        } else {
            log.warn("Unknown update. No action required");
        }
    }

    public void placeClosingOrdersAndScheduleCloseTask(Position position) {
        log.info("Placing closing orders...");

        Set<APIError> errors = new HashSet<>();
        CompletableFuture.allOf(
                CompletableFuture.runAsync(() -> placeStopOrder(position, errors)),
                CompletableFuture.runAsync(() -> placeFirstTakeOrder(position, errors)),
                CompletableFuture.runAsync(() -> placeSecondTakeOrder(position, errors))
        ).join();

        if (errors.isEmpty()) {
            state.set(Strategy.State.CLOSING_ORDERS_CREATED);
            log.info("Closing orders placed successfully. Transitioning to CLOSING_ORDERS_CREATED state.");
            scheduleAutoCloseTask();
        } else {
            if (errors.stream().anyMatch(apiError -> apiError.code() == -2022)) {
                log.warn("Got error 'ReduceOnly Order is rejected' which mean that position is empty");
                resetToEmptyPosition();
            } else if (errors.stream().anyMatch(apiError -> apiError.code() == -2021)) {
                log.warn("Got error 'Order would immediately trigger' - closing position due to fast price moving under limits");
                closePosition();
            } else if (errors.stream().anyMatch(apiError -> apiError.code() == -4015)) {
                log.warn("Got error 'Client order id is not valid', orders are placed, skipping...");
            } else {
                log.warn("Got unknown errors: " + errors);
                closePosition();
            }
        }
    }

    private void placeStopOrder(Position position, Set<APIError> errors) {
        HTTPResponse<Order, APIError> response = apiService.placeOrder(orderUtils.createStop(symbol, currentImbalance, position));
        if (response.isSuccess()) {
            orders.put(OrderType.STOP, response.getValue().getNewClientOrderId());
            log.info(String.format("Stop order placed: %s", response.getValue()));
        } else {
            errors.add(response.getError());
        }
    }

    private void placeFirstTakeOrder(Position position, Set<APIError> errors) {
        HTTPResponse<Order, APIError> response = apiService.placeOrder(orderUtils.createTake(symbol, position, currentImbalance.size(), 0));
        if (response.isSuccess()) {
            orders.put(OrderType.TAKE_0, response.getValue().getNewClientOrderId());
            log.info(String.format("First take order placed: %s", response.getValue()));
        } else {
            errors.add(response.getError());
        }
    }

    private void placeSecondTakeOrder(Position position, Set<APIError> errors) {
        HTTPResponse<Order, APIError> response = apiService.placeOrder(orderUtils.createTake(symbol, position, currentImbalance.size(), 1));
        if (response.isSuccess()) {
            orders.put(OrderType.TAKE_1, response.getValue().getNewClientOrderId());
            log.info(String.format("Second take order placed: %s", response.getValue()));
        } else {
            errors.add(response.getError());
        }
    }

    private void scheduleAutoCloseTask() {
        log.info("Scheduling auto-close position timer...");
        taskManager.schedule(
                AUTOCLOSE_POSITION_TASK_KEY,
                this::closeTimeout,
                TEST_RUN ? 5 : POSITION_LIVE_TIME,
                TimeUnit.MINUTES
        );
        log.info("Auto-close task scheduled.");
    }

    public void placeBreakEvenStop(Position position) {
        log.info("Placing break-even stop order...");
        if (orders.containsKey(OrderType.STOP)) {
            cancelExistingStopOrder();
        }
        HTTPResponse<Order, APIError> response = apiService.placeOrder(orderUtils.createBreakEvenStop(symbol, position));
        if (response.isSuccess()) {
            orders.put(OrderType.BREAK_EVEN, response.getValue().getNewClientOrderId());
            orders.remove(OrderType.TAKE_0);
            log.info(String.format("Break-even stop order placed successfully: %s", response.getValue()));
        } else if (response.getError().code() == -2021) {
            log.warn("Got error 'Order would immediately trigger' - closing position due to fast price moving under limits");
            closePosition();
        }
    }

    private void cancelExistingStopOrder() {
        try {
            String stopOrderId = orders.get(OrderType.STOP);
            log.info(String.format("Canceling existing stop order: %s", stopOrderId));
            apiService.cancelOrder(symbol, stopOrderId);
            orders.remove(OrderType.STOP);
            log.info("Existing stop order canceled.");
        } catch (Exception e) {
            log.error("Failed to cancel existing stop order", e);
        }
    }

    protected void closeTimeout() {
        log.info("Closing position due to timeout...");
        Order timeoutOrder = orderUtils.createClosePositionOrder(symbol, TIMEOUT_CLOSE_CLIENT_ID_PREFIX + System.currentTimeMillis());
        closePosition(OrderType.TIMEOUT, timeoutOrder);
    }

    public void closePosition() {
        log.info("Closing position due to previous errors...");
        Order closeOrder = orderUtils.createClosePositionOrder(symbol, CLOSE_CLIENT_ID_PREFIX + System.currentTimeMillis());
        closePosition(OrderType.CLOSE, closeOrder);
    }

    protected void closePosition(OrderType orderType, Order order) {
        synchronized (lock) {
            log.info(String.format("Executing close position task for order type: %s", orderType));
            Position position = apiService.getOpenPosition(symbol).getResponse();
            if (position == null) {
                log.warn("Position is already empty. Resetting state.");
                return;
            }
            order.setQuantity(Math.abs(position.getPositionAmt()));
            order.setSide(switch (position.getType()) {
                case SHORT -> Order.Side.BUY;
                case LONG -> Order.Side.SELL;
            });

            apiService.cancelAllOpenOrders(symbol);
            orders.clear();

            HTTPResponse<Order, APIError> response = apiService.placeOrder(order);
            if (response.isSuccess()) {
                orders.put(orderType, response.getValue().getNewClientOrderId());
                log.info(String.format("Position close order is placed. Order details: %s", response.getValue()));
            } else {
                log.error("Unexpected error during position closure.");
            }
        }
    }

    public void resetToEmptyPosition() {
        synchronized (lock) {
            log.info("Resetting state to POSITION_EMPTY...");
            taskManager.cancel(AUTOCLOSE_POSITION_TASK_KEY);
            state.set(Strategy.State.POSITION_EMPTY);
            currentImbalance = null;
            orders.clear();
            apiService.cancelAllOpenOrders(symbol);
        }
    }

    public Strategy.State getState() {
        return state.get();
    }

    public void setState(Strategy.State state) {
        this.state.set(state);
    }

    public Map<OrderType, String> getOrders() {
        return orders;
    }

    public Object getLock() {
        return lock;
    }

    public void logAll() {
        log.debug(String.format("""
                        TaskManager:
                            orders: %s
                            state: %s
                            currentImbalance: %s
                        """, orders, state, currentImbalance));

    }
}
