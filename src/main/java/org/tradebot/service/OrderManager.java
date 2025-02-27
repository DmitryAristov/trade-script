package org.tradebot.service;

import org.tradebot.binance.APIService;
import org.tradebot.binance.HttpClient;
import org.tradebot.domain.*;
import org.tradebot.listener.UserDataCallback;
import org.tradebot.strategy_state_handlers.StrategyStateDispatcher;
import org.tradebot.util.Log;
import org.tradebot.util.OrderUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.tradebot.util.Settings.*;

public class OrderManager implements UserDataCallback {

    public enum OrderType {
        OPEN, STOP, TAKE_0, TAKE_1, BREAK_EVEN, CLOSE, TIMEOUT
    }

    public enum State {
        POSITION_EMPTY,
        OPEN_ORDER_PLACED,
        OPEN_ORDER_FILLED,
        STOP_ORDERS_PLACED,
        FIRST_TAKE_FILLED,
        BREAK_EVEN_ORDER_CREATED
    }

    private final Log log;
    private final OrderUtils orderUtils;
    private final TaskManager taskManager;
    private final String baseAsset;
    private final boolean customLeverage;

    private final APIService apiService;
    private final StrategyStateDispatcher stateDispatcher;

    private final AtomicReference<State> state = new AtomicReference<>(State.POSITION_EMPTY);
    private final AtomicReference<Imbalance> currentImbalance = new AtomicReference<>();

    private final Map<OrderType, Order> orders = new ConcurrentHashMap<>();
    private final AtomicReference<Position> position = new AtomicReference<>();

    public OrderManager(HttpClient httpClient,
                        int clientNumber,
                        String baseAsset,
                        boolean customLeverage,
                        StrategyStateDispatcher dispatcher) {
        this.apiService = new APIService(httpClient, clientNumber);
        this.taskManager = TaskManager.getInstance(clientNumber);
        this.baseAsset = baseAsset;
        this.customLeverage = customLeverage;
        this.stateDispatcher = dispatcher;
        this.log = new Log(clientNumber);
        this.orderUtils = new OrderUtils(clientNumber);

        log.info("OrderManager initialized");
    }

    public void placeOpenOrder(final Imbalance imbalance, double price) {
        if (state.get() != State.POSITION_EMPTY) {
            log.info(String.format("Attempted to place open order in invalid state: %s. Action aborted.", state.get()));
            return;
        }

        if (position.get() != null) {
            log.info(String.format("Attempted to place open order when position already opened: %s. Action aborted.", position.get()));
            return;
        }

        currentImbalance.set(imbalance);
        int leverage = LEVERAGE;
        if (customLeverage) {
            leverage = apiService.getLeverage(SYMBOL).getResponse();
            log.info("Client is using custom leverage: " + leverage);
        }

        double balance = apiService.getAccountBalance(baseAsset).getResponse();
        log.info(String.format("Client balance when opening position: %.2f", balance));

        double quantity = balance * RISK_LEVEL * leverage / price;
        log.info(String.format("Computed quantity: %.5f", quantity));

        log.info("Opening position...");
        Order open = orderUtils.createOpen(SYMBOL, imbalance, quantity);
        orders.put(OrderType.OPEN, open);
        HTTPResponse<Order> response = apiService.placeOrder(open);

        if (response.isSuccess()) {
            state.set(State.OPEN_ORDER_PLACED);
            log.info("Open order placed: " + response.getValue());
        } else {
            orders.remove(OrderType.OPEN);
            throw log.throwError("Failed to open position: " + response.getError());
        }
    }

    private int userMessagesCount = 0;
    @Override
    public void notifyOrderUpdate(String clientId, String status) {
        log.info(String.format("Received order update: %s - %s", clientId, status));

        if (clientId == null) {
            log.info("Order update received without client ID. Skipping...");
            return;
        }

        if (!Order.Status.FILLED.toString().equals(status)) {
            return;
        }

        if (TEST_RUN && SIMULATE_WEB_SOCKET_LOST_MESSAGES) {
            userMessagesCount++;
            if (userMessagesCount % 11 == 0) {
                log.info("Simulating missing order update for order: " + clientId);
                return;
            }
        }

        orders.values().stream()
                .filter(order -> clientId.equals(order.getNewClientOrderId()))
                .findFirst()
                .ifPresent(order -> order.setStatus(Order.Status.FILLED));

        Order openOrder = orders.get(OrderType.OPEN);
        Order take0Order = orders.get(OrderType.TAKE_0);

        if (openOrder != null && clientId.equals(openOrder.getNewClientOrderId())) {
            log.info("Open order filled. Transitioning to POSITION_OPENED state.");
            state.set(State.OPEN_ORDER_FILLED);
            taskManager.schedule(HANDLE_OPEN_ORDER_FILLED_TASK_KEY, this::handleOpenOrderFilled, 0, TimeUnit.MILLISECONDS);
        } else if (take0Order != null && clientId.equals(take0Order.getNewClientOrderId())) {
            log.info("First take order filled. Handling...");
            state.set(State.FIRST_TAKE_FILLED);
            taskManager.schedule(HANDLE_TAKE_0_ORDER_FILLED_TASK_KEY, this::handleFirstTakeOrderFilled, 0, TimeUnit.MILLISECONDS);
        } else if (isClosingOrder(clientId)) {
            log.info(clientId + " order filled. Resetting...");
            taskManager.schedule(HANDLE_CLOSE_ORDER_FILLED_TASK_KEY, this::closePositionAndResetState, 0, TimeUnit.MILLISECONDS);
        } else {
            log.info("Unknown order filled - " + clientId + ". Handling...");
            taskManager.schedule(HANDLE_UNKNOWN_ORDER_FILLED_TASK_KEY, this::handleUnknownFilledOrder, 0, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void notifyPositionUpdate(Position position) {
        log.info("Received position update: " + position);
        this.position.set(position);
    }

    public void handleOpenOrderFilled() {
        log.info("Placing closing orders...");

        CompletableFuture<Boolean> stopOrderPlacedFuture = CompletableFuture.supplyAsync(this::placeStopOrder);
        CompletableFuture<Boolean> firstTakeOrderPlacedFuture = CompletableFuture.supplyAsync(this::placeFirstTakeOrder);
        CompletableFuture<Boolean> secondTakeOrderPlacedFuture = CompletableFuture.supplyAsync(this::placeSecondTakeOrder);

        try {
            if (!stopOrderPlacedFuture.get()) return;
            if (!firstTakeOrderPlacedFuture.get()) return;
            if (!secondTakeOrderPlacedFuture.get()) return;
        } catch (Exception e) {
            log.error("Failed to place stop orders", e);
            return;
        }
        scheduleAutoCloseTask();

        state.set(State.STOP_ORDERS_PLACED);
        log.info("Stop orders placed successfully. Transitioning to STOP_ORDERS_PLACED state.");
    }

    public void handleFirstTakeOrderFilled() {
        if (position.get() == null) {
            log.warn("Position is empty when first take order is filled.");
            scheduleUnexpectedErrorHandlerTask();
            return;
        }

        cancelStopOrder();
        placeBreakEvenOrder();
    }

    public void handleUnknownFilledOrder() {
        log.info("Handling unknown order update...");
        if (state.get() == State.POSITION_EMPTY) {
            log.info("Unknown update in empty state. No action required");
            return;
        }

        if (position.get() != null) {
            log.info("Unknown update with empty position. No action required");
            return;
        }

        log.info("Manual position close detected. Resetting state...");
        taskManager.schedule(HANDLE_CLOSE_POSITION_TASK_KEY, this::closePositionAndResetState, 0, TimeUnit.MILLISECONDS);
    }

    public void closePositionAndResetState() {
        log.info("Resetting state to POSITION_EMPTY...");

        taskManager.cancelForce(HANDLE_OPEN_ORDER_FILLED_TASK_KEY);
        taskManager.cancelForce(HANDLE_TAKE_0_ORDER_FILLED_TASK_KEY);
        taskManager.cancelForce(HANDLE_UNKNOWN_ORDER_FILLED_TASK_KEY);
        taskManager.cancel(AUTOCLOSE_POSITION_TASK_KEY);

        apiService.cancelAllOpenOrders(SYMBOL);
        orders.clear();

        if (position.get() != null) {
            log.info("Position is not empty, closing...");
            closePosition(OrderType.CLOSE, CLOSE_CLIENT_ID_PREFIX + System.currentTimeMillis());
        } else {
            log.info("Position is closed.");
            state.set(State.POSITION_EMPTY);
        }
    }

    public void checkOrdersAPI() {
        log.debug("Sending API requests for position and orders...");
        try {
            CompletableFuture<Position> positionFuture = CompletableFuture.supplyAsync(() ->
                    apiService.getOpenPosition(SYMBOL).getResponse());
            CompletableFuture<List<Order>> openedOrdersFuture = CompletableFuture.supplyAsync(() ->
                    apiService.getOpenOrders(SYMBOL).getResponse());
            Position position = positionFuture.get();
            List<Order> openedOrders = openedOrdersFuture.get();
            log.debug("Received API responses. Dispatching state update...");
            stateDispatcher.dispatch(position, openedOrders, state.get());
        } catch (Exception e) {
            log.error("Failed to retrieve data via API", e);
        }
    }

    private boolean isClosingOrder(String clientId) {
        Order close = orders.get(OrderType.CLOSE);
        Order stop = orders.get(OrderType.STOP);
        Order take1 = orders.get(OrderType.TAKE_1);
        Order breakEven = orders.get(OrderType.BREAK_EVEN);
        Order timeout = orders.get(OrderType.TIMEOUT);
        return (close != null && clientId.equals(close.getNewClientOrderId())) ||
                (stop != null && clientId.equals(stop.getNewClientOrderId())) ||
                (take1 != null && clientId.equals(take1.getNewClientOrderId())) ||
                (breakEven != null && clientId.equals(breakEven.getNewClientOrderId())) ||
                (timeout != null && clientId.equals(timeout.getNewClientOrderId()));
    }

    private boolean placeStopOrder() {
        Order stop = orderUtils.createStop(SYMBOL, currentImbalance.get(), position.get());
        orders.putIfAbsent(OrderType.STOP, stop);
        HTTPResponse<Order> response = apiService.placeOrder(stop);

        if (response.isSuccess()) {
            Order order = response.getValue();
            log.info("Stop order placed: " + order);
            return true;
        }

        APIError error = response.getError();
        if (orderWouldImmediatelyTrigger(error)) {
            orders.remove(OrderType.STOP);
            return false;
        }

        if (orderAlreadyPlaced(error, OrderType.STOP)) {
            if (shouldReplaceOrder(OrderType.STOP)) {
                log.info("Stop order is not correct, replacing...");
                var response_ = apiService.modifyOrder(stop);
                if (response_.isSuccess()) {
                    log.info("Stop order modified successfully.");
                    return true;
                } else {
                    log.info("Modification error, replacing with new order...");
                    apiService.cancelOrder(SYMBOL, orders.get(OrderType.STOP).getNewClientOrderId());
                    return placeStopOrder();
                }
            }
            log.info("Stop order is correct.");
            return true;
        }

        log.warn("Failed to place stop: " + error);
        scheduleUnexpectedErrorHandlerTask();
        orders.remove(OrderType.STOP);
        return false;
    }

    private boolean placeFirstTakeOrder() {
        Order take = orderUtils.createFirstTake(SYMBOL, position.get(), currentImbalance.get().size());
        orders.putIfAbsent(OrderType.TAKE_0, take);
        HTTPResponse<Order> response = apiService.placeOrder(take);

        if (response.isSuccess()) {
            Order order = response.getValue();
            log.info("First take order placed: " + order);
            return true;
        }

        APIError error = response.getError();
        if (isOrderRejected(error)) {
            orders.remove(OrderType.TAKE_0);
            return false;
        }

        if (orderAlreadyPlaced(error, OrderType.TAKE_0)) {
            if (shouldReplaceOrder(OrderType.TAKE_0)) {
                log.info("First take order is not correct, replacing...");
                var response_ = apiService.modifyOrder(take);
                if (response_.isSuccess()) {
                    log.info("First take order modified successfully.");
                    return true;
                } else {
                    log.info("Modification error, replacing with new order...");
                    apiService.cancelOrder(SYMBOL, orders.get(OrderType.TAKE_0).getNewClientOrderId());
                    return placeFirstTakeOrder();
                }
            }
            log.info("First take order is correct.");
            return true;
        }

        log.warn("Failed to place first take: " + error);
        scheduleUnexpectedErrorHandlerTask();
        orders.remove(OrderType.TAKE_0);
        return false;
    }

    private boolean placeSecondTakeOrder() {
        Order take = orderUtils.createSecondTake(SYMBOL, position.get(), currentImbalance.get().size());
        orders.putIfAbsent(OrderType.TAKE_1, take);
        HTTPResponse<Order> response = apiService.placeOrder(take);

        if (response.isSuccess()) {
            Order order = response.getValue();
            log.info("Second take order placed: " + order);
            return true;
        }

        APIError error = response.getError();
        if (isOrderRejected(error)) {
            orders.remove(OrderType.TAKE_1);
            return false;
        }

        if (orderAlreadyPlaced(error, OrderType.TAKE_1)) {
            if (shouldReplaceOrder(OrderType.TAKE_1)) {
                log.info("Second take order is not correct, replacing...");
                var response_ = apiService.modifyOrder(take);
                if (response_.isSuccess()) {
                    log.info("Second take order modified successfully.");
                    return true;
                } else {
                    log.info("Modification error, replacing with new order...");
                    apiService.cancelOrder(SYMBOL, orders.get(OrderType.TAKE_1).getNewClientOrderId());
                    return placeSecondTakeOrder();
                }
            }
            log.info("Second take order is correct.");
            return true;
        }

        log.warn("Failed to place second take: " + error);
        scheduleUnexpectedErrorHandlerTask();
        orders.remove(OrderType.TAKE_1);
        return false;
    }

    private void scheduleAutoCloseTask() {
        log.info("Scheduling auto-close position timer...");
        taskManager.schedule(
                AUTOCLOSE_POSITION_TASK_KEY,
                () -> {
                    log.info("Closing position due to timeout...");
                    closePosition(OrderType.TIMEOUT, TIMEOUT_CLOSE_CLIENT_ID_KEY);
                },
                TEST_RUN ? 5 : POSITION_LIVE_TIME,
                TimeUnit.MINUTES
        );
        log.info("Auto-close task scheduled.");
    }

    private void cancelStopOrder() {
        Order stop = orders.get(OrderType.STOP);
        if (stop != null && stop.getStatus() != Order.Status.CANCELED) {
            String stopOrderId = orders.get(OrderType.STOP).getNewClientOrderId();
            HTTPResponse<String> response = apiService.cancelOrder(SYMBOL, stopOrderId);
            log.info(String.format("Canceling existing stop order: %s...", stopOrderId));

            if (response.isSuccess()) {
                stop.setStatus(Order.Status.CANCELED);
                log.info("Existing stop order canceled.");
                return;
            }

            log.warn("Failed to cancel stop order: " + response.getError());
            scheduleUnexpectedErrorHandlerTask();
        }
    }

    private void placeBreakEvenOrder() {
        Order breakEven = orderUtils.createBreakEvenStop(SYMBOL, position.get());
        orders.putIfAbsent(OrderType.BREAK_EVEN, breakEven);
        HTTPResponse<Order> response = apiService.placeOrder(breakEven);

        if (response.isSuccess()) {
            state.set(State.BREAK_EVEN_ORDER_CREATED);
            log.info("Break-even stop order placed successfully: " + response.getValue());
            return;
        }

        orders.remove(OrderType.BREAK_EVEN);
        if (orderWouldImmediatelyTrigger(response.getError())) {
            return;
        }

        log.warn("Failed to place break-even stop order: " + response.getError());
        scheduleUnexpectedErrorHandlerTask();
    }

    private void closePosition(OrderType orderType, String clientId) {
        if (position.get() == null) {
            log.warn("Trying to close empty position.");
            scheduleUnexpectedErrorHandlerTask();
            return;
        }

        log.info("Closing position with order type: " + orderType);
        Order order = orderUtils.createClosePosition(SYMBOL, clientId, position.get());
        orders.put(orderType, order);
        HTTPResponse<Order> response = apiService.placeOrder(order);

        if (response.isSuccess()) {
            log.info("Position close order is placed. Order details: " + response.getValue());
            return;
        } else if (TEST_RUN) {
            taskManager.schedule(HANDLE_CLOSE_POSITION_TASK_KEY, this::closePositionAndResetState, 5, TimeUnit.MILLISECONDS);
        }

        log.warn("Failed to place close position order: " + response.getError());
        orders.remove(orderType);
        scheduleUnexpectedErrorHandlerTask();
    }

    private boolean orderWouldImmediatelyTrigger(APIError error) {
        if (error.code() == -2021) {
            log.info("Got error 'Order would immediately trigger' - closing position due to fast price moving under limits");
            taskManager.schedule(HANDLE_CLOSE_POSITION_TASK_KEY, this::closePositionAndResetState, 5, TimeUnit.MILLISECONDS);
            return true;
        }
        return false;
    }

    private boolean isOrderRejected(APIError error) {
        if (error.code() == -2022) {
            log.info("Got error 'ReduceOnly Order is rejected' which mean that position is empty");
            taskManager.schedule(HANDLE_CLOSE_POSITION_TASK_KEY, this::closePositionAndResetState, 5, TimeUnit.MILLISECONDS);
            return true;
        }
        return false;
    }

    private boolean orderAlreadyPlaced(APIError error, OrderType orderType) {
        if (error.code() == -4015) {
            log.info(String.format("Got error 'Client order id is not valid', order %s is placed, checking...", orderType));
            return true;
        }
        return false;
    }

    private boolean shouldReplaceOrder(OrderType orderType) {
        log.info("Comparing orders " + orderType);
        Order localOrder = orders.get(orderType);
        HTTPResponse<Order> response = apiService.queryOrder(SYMBOL, localOrder.getNewClientOrderId());
        Order actualOrder = response.getValue();
        log.debug("Local order " + localOrder);
        log.debug("Actual order " + actualOrder);

        return localOrder.getSide() != actualOrder.getSide() || switch (orderType) {
            case STOP -> localOrder.getStopPrice().doubleValue() != actualOrder.getStopPrice().doubleValue();
            case TAKE_0, TAKE_1 -> localOrder.getPrice().doubleValue() != actualOrder.getPrice().doubleValue();
            default -> throw log.throwError("Unsupported type");
        };
    }

    private void scheduleUnexpectedErrorHandlerTask() {
        taskManager.schedule(CHECK_ORDERS_API_MODE_TASK_KEY, this::checkOrdersAPI, 1, TimeUnit.SECONDS);
//        taskManager.scheduleAtFixedRate(UNEXPECTED_BOT_STATE_TASK_KEY, () -> {
//            log.info("Unexpected program state got. Checking...");
//            apiCheckProgress.set(true);
//
//            try {
//                CompletableFuture<HTTPResponse<Position>> positionResponseFuture = CompletableFuture.supplyAsync(() ->
//                        operationHelper.performWithRetry(() -> apiService.getOpenPosition(SYMBOL)));
//                CompletableFuture<HTTPResponse<List<Order>>> ordersResponseFuture = CompletableFuture.supplyAsync(() ->
//                        operationHelper.performWithRetry(() -> apiService.getOpenOrders(SYMBOL)));
//
//                HTTPResponse<Position> positionResponse = positionResponseFuture.get();
//                HTTPResponse<List<Order>> ordersResponse = ordersResponseFuture.get();
//
//                if (positionResponse.isSuccess() && ordersResponse.isSuccess()) {
//                    Position actualPosition = positionResponse.getValue();
//                    checkPositionAPI(actualPosition, ordersResponse.getValue());
//                    log.info("Position is up to date.");
//                } else {
//                    log.error("HTTP connection failure: " + positionResponse.getError());
//                }
//            } catch (Exception e) {
//                log.error("HTTP connection failure.", e);
//            } finally {
//                apiCheckProgress.set(false);
//            }
//
//        }, 2, 2, TimeUnit.SECONDS);
    }

//    private void checkPositionAPI(Position actualPosition, List<Order> actualOrders) {
//        if (actualPosition == null) {
//            log.info("Actual position is empty. Resetting...");
//            closePositionAndResetState();
//            position.set(null);
//            apiCheckProgress.set(false);
//            taskManager.cancel(UNEXPECTED_BOT_STATE_TASK_KEY);
//            return;
//        }
//
//        log.info("Actual position is not empty.");
//        log.debug(actualPosition.toString());
//        log.debug(String.valueOf(position.get()));
//        position.set(actualPosition);
//
//        Order open = orders.get(OrderType.OPEN);
//        if (open == null) {
//            log.error("Open order is not present in local orders.");
//            return;
//        }
//
//        if (position.get().getPositionAmt() == open.getQuantity().doubleValue()) {
//            boolean stopValid = validateOrder(OrderType.STOP, actualOrders);
//            boolean take0Valid = validateOrder(OrderType.TAKE_0, actualOrders);
//            boolean take1Valid = validateOrder(OrderType.TAKE_1, actualOrders);
//            if (!stopValid || !take0Valid || !take1Valid) {
//                log.info("Missed some/all of 3 stop orders. Placing...");
//                handleOpenOrderFilled();
//            }
//            apiCheckProgress.set(false);
//            taskManager.cancel(UNEXPECTED_BOT_STATE_TASK_KEY);
//            return;
//        }
//
//        boolean breakEvenValid = validateOrder(OrderType.BREAK_EVEN, actualOrders);
//        boolean take1Valid = validateOrder(OrderType.TAKE_1, actualOrders);
//        if (!breakEvenValid || !take1Valid) {
//            log.info("Missed some/all of 2 stop orders. Placing...");
//            handleFirstTakeOrderFilled();
//        }
//        apiCheckProgress.set(false);
//        taskManager.cancel(UNEXPECTED_BOT_STATE_TASK_KEY);
//    }

//    private boolean validateOrder(OrderType orderType, List<Order> actualOrders) {
//        Order local = orders.get(orderType);
//        if (local == null) {
//            log.info(String.format("%s order client ID is null in local orders.", orderType));
//            return false;
//        }
//        boolean exists = actualOrders.stream()
//                .anyMatch(order -> local.getNewClientOrderId().equals(order.getNewClientOrderId()));
//        log.info(orderType + " order exists in actual orders list = " + exists);
//        return exists;
//    }

    public synchronized State getState() {
        return state.get();
    }

    public synchronized void setState(State state) {
        this.state.set(state);
    }

    public Map<OrderType, Order> getOrders() {
        return this.orders;
    }

    public void logAll() {
        try {
            log.debug(String.format("""
                            OrderManager state:
                            state: %s
                            currentImbalance: %s
                            orders: %s
                            position: %s
                            """,
                    state.get(), currentImbalance.get(), orders, position.get()));
        } catch (Exception e) {
            log.warn("Failed to write", e);
        }
    }
}
