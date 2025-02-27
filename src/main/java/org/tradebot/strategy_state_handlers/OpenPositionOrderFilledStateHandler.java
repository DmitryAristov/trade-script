package org.tradebot.strategy_state_handlers;

import org.jetbrains.annotations.Nullable;
import org.tradebot.domain.Order;
import org.tradebot.domain.Position;
import org.tradebot.service.OrderManager;
import org.tradebot.util.Log;

import java.util.List;

public class OpenPositionOrderFilledStateHandler implements StateHandler {

    private final Log log;
    private final OrderManager orderManager;

    public OpenPositionOrderFilledStateHandler(OrderManager orderManager, int clientNumber) {
        this.orderManager = orderManager;
        this.log = new Log(clientNumber);
        log.info("PositionOpenedStateHandler initialized");
    }

    @Override
    public void handle(@Nullable Position position, List<Order> openedOrders) {
        orderManager.notifyPositionUpdate(position);
        if (position == null) {
            log.warn("Position is empty, but local state is POSITION_OPENED.");
            log.debug(String.format("Local orders: %s", orderManager.getOrders()));
            orderManager.closePositionAndResetState();
            log.info("State reset to EMPTY_POSITION.");
        } else {
            log.debug(String.format("Position: %s", position));
            handleNonEmptyPosition(openedOrders);
        }
    }

    private void handleNonEmptyPosition(List<Order> openedOrders) {
        log.info("Position is present. Validating closing orders...");
        if (openedOrders.isEmpty()) {
            log.warn("No closing orders found. Placing closing orders...");
            orderManager.handleOpenOrderFilled();
        } else {
            log.info("position is present and closing orders are not empty, check all required closing orders are placed");
            if (validateAllClosingOrdersPlaced(openedOrders)) {
                log.info("All required closing orders are valid. Switching state to CLOSING_ORDERS_CREATED.");
                orderManager.setState(OrderManager.State.STOP_ORDERS_PLACED);
            } else {
                handleInvalidClosingOrders(openedOrders);
            }
        }
    }

    private void handleInvalidClosingOrders(List<Order> openedOrders) {
        log.warn("Invalid or missing closing orders detected.");
        log.debug(String.format("Opened orders: %s", openedOrders));
        log.debug(String.format("Local orders: %s", orderManager.getOrders()));

        log.info("Closing position and resetting state...");
        orderManager.handleOpenOrderFilled();
        log.info("State reset to EMPTY_POSITION.");
    }

    private boolean validateAllClosingOrdersPlaced(List<Order> openedOrders) {
        boolean stopLossValid = validateClosingOrder(OrderManager.OrderType.STOP, openedOrders);
        boolean take0Valid = validateClosingOrder(OrderManager.OrderType.TAKE_0, openedOrders);
        boolean take1Valid = validateClosingOrder(OrderManager.OrderType.TAKE_1, openedOrders);

        if (!stopLossValid || !take0Valid || !take1Valid) {
            log.warn("Not all required closing orders are valid.");
            log.debug(String.format("Opened orders: %s", openedOrders));
            log.debug(String.format("Local orders: %s", orderManager.getOrders()));
            return false;
        }
        return true;
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