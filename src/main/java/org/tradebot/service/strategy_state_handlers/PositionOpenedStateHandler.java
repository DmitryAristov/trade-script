package org.tradebot.service.strategy_state_handlers;

import org.tradebot.binance.APIService;
import org.tradebot.domain.Order;
import org.tradebot.domain.Position;
import org.tradebot.service.OrderManager;
import org.tradebot.service.Strategy;
import org.tradebot.service.OrderManager.OrderType;
import org.tradebot.util.Log;

import java.util.List;

public class PositionOpenedStateHandler implements StateHandler {
    private final Log log = new Log();

    private final APIService apiService;
    private final OrderManager orderManager;
    private final String symbol;

    public PositionOpenedStateHandler(APIService apiService,
                                      OrderManager orderManager,
                                      String symbol) {
        this.apiService = apiService;
        this.orderManager = orderManager;
        this.symbol = symbol;
        log.info("PositionOpenedStateHandler initialized");
    }

    @Override
    public void handle(Position position, List<Order> openedOrders) {
        log.info("Handling POSITION_OPENED state...");
        if (position == null) {
            handleEmptyPosition(openedOrders);
        } else {
            handleNonEmptyPosition(position, openedOrders);
        }
    }

    private void handleEmptyPosition(List<Order> openedOrders) {
        log.warn("Position is empty, but local state is POSITION_OPENED.");
        log.debug(String.format("Local orders: %s", orderManager.getOrders()));

        if (!openedOrders.isEmpty()) {
            log.warn("Found opened orders while position is empty. Cancelling all opened orders...");
            log.debug(String.format("Opened orders: %s", openedOrders));
            apiService.cancelAllOpenOrders(symbol);
            orderManager.getOrders().clear();
            log.info("All opened orders have been cancelled.");
        }
        orderManager.resetToEmptyPosition();
        log.info("State reset to EMPTY_POSITION.");
    }

    private void handleNonEmptyPosition(Position position, List<Order> openedOrders) {
        log.info("Position is present. Validating closing orders...");
        if (openedOrders.isEmpty()) {
            log.warn("No closing orders found. Placing closing orders...");
            orderManager.placeClosingOrdersAndScheduleCloseTask(position);
        } else {
            log.info("position is present and closing orders are not empty, check all required closing orders are placed");
            if (validateAllClosingOrdersPlaced(openedOrders)) {
                log.info("All required closing orders are valid. Switching state to CLOSING_ORDERS_CREATED.");
                orderManager.setState(Strategy.State.CLOSING_ORDERS_CREATED);
            } else {
                handleInvalidClosingOrders(position, openedOrders);
            }
        }
    }

    private void handleInvalidClosingOrders(Position position, List<Order> openedOrders) {
        log.warn("Invalid or missing closing orders detected.");
        log.debug(String.format("Opened orders: %s", openedOrders));
        log.debug(String.format("Local orders: %s", orderManager.getOrders()));
        log.debug(String.format("Position: %s", position));

        log.info("Closing position and resetting state...");
        orderManager.close(position);
        orderManager.resetToEmptyPosition();
        log.info("State reset to EMPTY_POSITION.");
    }

    private boolean validateAllClosingOrdersPlaced(List<Order> openedOrders) {
        boolean stopLossValid = validateClosingOrder(OrderType.STOP, openedOrders);
        boolean take0Valid = validateClosingOrder(OrderType.TAKE_0, openedOrders);
        boolean take1Valid = validateClosingOrder(OrderType.TAKE_1, openedOrders);

        if (!stopLossValid || !take0Valid || !take1Valid) {
            log.warn("Not all required closing orders are valid.");
            log.debug(String.format("Opened orders: %s", openedOrders));
            log.debug(String.format("Local orders: %s", orderManager.getOrders()));
            return false;
        }
        return true;
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