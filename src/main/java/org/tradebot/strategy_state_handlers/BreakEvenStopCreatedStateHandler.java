package org.tradebot.strategy_state_handlers;

import org.jetbrains.annotations.Nullable;
import org.tradebot.binance.APIService;
import org.tradebot.domain.Order;
import org.tradebot.domain.Position;
import org.tradebot.service.OrderManager;
import org.tradebot.util.Log;

import java.util.List;

import static org.tradebot.util.Settings.SYMBOL;

public class BreakEvenStopCreatedStateHandler implements StateHandler {

    private final Log log;
    private final OrderManager orderManager;
    private final APIService apiService;

    public BreakEvenStopCreatedStateHandler(APIService apiService,
                                            OrderManager orderManager,
                                            int clientNumber) {
        this.orderManager = orderManager;
        this.apiService = apiService;
        this.log = new Log(clientNumber);
        log.info("BreakEvenStopCreatedStateHandler initialized.");
    }

    @Override
    public void handle(@Nullable Position position, List<Order> openedOrders) {
        orderManager.notifyPositionUpdate(position);
        if (position == null) {
            log.info("Position is closed. Resetting state to initial.");
            orderManager.closePositionAndResetState();
        } else {
            log.info("Position is open. Validating set of closing orders...");
            if (!validateClosingOrdersState(openedOrders)) {
                log.warn("Invalid set of closing orders detected.");
                log.debug(String.format("Position: %s", position));
                log.debug(String.format("Local orders: %s", orderManager.getOrders()));
                log.debug(String.format("Actual opened orders: %s", openedOrders));
                log.info("Closing position and resetting state.");
                orderManager.closePositionAndResetState();
            } else {
                log.info("Waiting for position to close...");
            }
        }
    }

    private boolean validateClosingOrdersState(List<Order> openedOrders) {
        log.info("Validating closing orders...");
        int orderCount = openedOrders.size();
        log.debug(String.format("Opened orders count: %d", orderCount));

        if (orderCount == 2) {
            log.info("2 opened orders detected. Validating appropriate set...");
            return validateTwoOrdersState(openedOrders);
        }
        log.warn(String.format("Unexpected number of opened orders: %d", orderCount));
        return false;
    }

    private boolean validateTwoOrdersState(List<Order> openedOrders) {
        Order take0 = orderManager.getOrders().get(OrderManager.OrderType.TAKE_0);
        if (take0 != null) {
            log.info("First take order is present locally.");
            var take0Opt = openedOrders.stream()
                    .filter(order -> take0.getNewClientOrderId().equals(order.getNewClientOrderId()))
                    .findFirst();
            if (take0Opt.isEmpty()) {
                log.info("First take order not found in opened orders. Querying order status...");
                Order firstTake = apiService.queryOrder(SYMBOL, take0.getNewClientOrderId()).getResponse();
                log.info(String.format("First take order status: %s", firstTake.getStatus()));

                return handleTake0OrderStatus(firstTake);
            } else {
                log.info("First take order is still opened. No further action required.");
                return false;
            }
        } else {
            log.info("First take order filled. Validating remaining orders: TAKE_1 and BREAK_EVEN.");
            boolean take1Valid = validateClosingOrder(OrderManager.OrderType.TAKE_1, openedOrders);
            boolean breakEvenValid = validateClosingOrder(OrderManager.OrderType.BREAK_EVEN, openedOrders);
            return take1Valid && breakEvenValid;
        }
    }

    private boolean handleTake0OrderStatus(Order take0) {
        switch (take0.getStatus()) {
            case FILLED -> {
                log.info("First take order filled. Placing break-even stop.");
                orderManager.handleFirstTakeOrderFilled();
                return true;
            }
            case PARTIALLY_FILLED -> {
                log.info("First take order partially filled. Waiting for full fill.");
                return true;
            }
            default -> {
                log.warn(String.format("Unexpected status for first take order: %s", take0.getStatus()));
                return false;
            }
        }
    }

    private boolean validateClosingOrder(OrderManager.OrderType orderType, List<Order> openedOrders) {
        Order localOrder = orderManager.getOrders().get(orderType);
        if (localOrder == null) {
            log.warn(String.format("%s order is null in local orders.", orderType));
            return false;
        }

        boolean isValid = openedOrders.stream()
                .anyMatch(order -> localOrder.getNewClientOrderId().equals(order.getNewClientOrderId()));

        if (isValid) {
            log.debug(String.format("%s order is valid in opened orders.", orderType));
        } else {
            log.warn(String.format("%s order is not found in opened orders.", orderType));
        }
        return isValid;
    }
}