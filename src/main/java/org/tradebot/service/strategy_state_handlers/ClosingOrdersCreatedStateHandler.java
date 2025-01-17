package org.tradebot.service.strategy_state_handlers;

import org.tradebot.binance.APIService;
import org.tradebot.domain.Order;
import org.tradebot.domain.Position;
import org.tradebot.service.OrderManager;
import org.tradebot.service.OrderManager.OrderType;
import org.tradebot.util.Log;

import java.util.List;

public class ClosingOrdersCreatedStateHandler implements StateHandler {
    private final Log log = new Log();

    private final APIService apiService;
    private final OrderManager orderManager;
    private final String symbol;

    public ClosingOrdersCreatedStateHandler(APIService apiService,
                                            OrderManager orderManager,
                                            String symbol) {
        this.apiService = apiService;
        this.orderManager = orderManager;
        this.symbol = symbol;
        log.info("ClosingOrdersCreatedStateHandler initialized");
    }

    @Override
    public void handle(Position position, List<Order> openedOrders) {
        log.info("Handling CLOSING_ORDERS_CREATED state...");
        if (position == null) {
            log.info("Position is closed. Resetting state to initial.");
            orderManager.resetToEmptyPosition();
        } else {
            log.info("Position is open. Validating set of closing orders...");
            if (!validateClosingOrdersState(openedOrders, position)) {
                log.warn("Invalid set of closing orders detected.");
                log.debug(String.format("Position: %s", position));
                log.debug(String.format("Local orders: %s", orderManager.getOrders()));
                log.debug(String.format("Actual opened orders: %s", openedOrders));
                log.info("Closing position and resetting state.");
                orderManager.closePosition();
                orderManager.resetToEmptyPosition();
            } else {
                log.info("Waiting for position to close...");
            }
        }
    }

    private boolean validateClosingOrdersState(List<Order> openedOrders, Position position) {
        log.info("Validating closing orders...");
        int orderCount = openedOrders.size();
        log.debug(String.format("Opened orders count: %d", orderCount));

        switch (orderCount) {
            case 3 -> {
                log.info("3 opened orders detected. Validating STOP, TAKE_0, and TAKE_1...");
                boolean stopLossValid = validateClosingOrder(OrderType.STOP, openedOrders);
                boolean take0Valid = validateClosingOrder(OrderType.TAKE_0, openedOrders);
                boolean take1Valid = validateClosingOrder(OrderType.TAKE_1, openedOrders);
                return stopLossValid && take0Valid && take1Valid;
            }
            case 2 -> {
                log.info("2 opened orders detected. Validating appropriate set...");
                return validateTwoOrdersState(openedOrders, position);
            }
            default -> {
                log.warn(String.format("Unexpected number of opened orders: %d", orderCount));
                return false;
            }
        }
    }

    private boolean validateTwoOrdersState(List<Order> openedOrders, Position position) {
        if (orderManager.getOrders().containsKey(OrderType.TAKE_0)) {
            log.info("First take order is present locally.");
            var take0Opt = openedOrders.stream()
                    .filter(order -> orderManager.getOrders().get(OrderType.TAKE_0).equals(order.getNewClientOrderId()))
                    .findFirst();
            if (take0Opt.isEmpty()) {
                log.info("First take order not found in opened orders. Querying order status...");
                Order firstTake = apiService.queryOrder(symbol, orderManager.getOrders().get(OrderType.TAKE_0)).getResponse();
                log.info(String.format("First take order status: %s", firstTake.getStatus()));

                return handleTake0OrderStatus(firstTake, position);
            } else {
                log.info("First take order is still opened. No further action required.");
                return false;
            }
        } else {
            log.info("First take order filled. Validating remaining orders: TAKE_1 and BREAK_EVEN.");
            boolean take1Valid = validateClosingOrder(OrderType.TAKE_1, openedOrders);
            boolean breakEvenValid = validateClosingOrder(OrderType.BREAK_EVEN, openedOrders);
            return take1Valid && breakEvenValid;
        }
    }

    private boolean handleTake0OrderStatus(Order take0, Position position) {
        switch (take0.getStatus()) {
            case FILLED -> {
                log.info("First take order filled. Placing break-even stop.");
                orderManager.placeBreakEvenStop(position);
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

    private boolean validateClosingOrder(OrderType orderType, List<Order> openedOrders) {
        String localOrderId = orderManager.getOrders().get(orderType);
        if (localOrderId == null) {
            log.warn(String.format("%s order client ID is null in local orders.", orderType));
            return false;
        }

        boolean isValid = openedOrders.stream()
                .anyMatch(order -> localOrderId.equals(order.getNewClientOrderId()));

        if (isValid) {
            log.debug(String.format("%s order is valid in opened orders.", orderType));
        } else {
            log.warn(String.format("%s order is not found in opened orders.", orderType));
        }
        return isValid;
    }
}
