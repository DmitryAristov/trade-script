package org.tradebot.strategy_state_handlers;

import org.jetbrains.annotations.Nullable;
import org.tradebot.binance.APIService;
import org.tradebot.domain.Order;
import org.tradebot.domain.Position;
import org.tradebot.service.OrderManager;
import org.tradebot.util.Log;

import java.util.List;

import static org.tradebot.util.Settings.SYMBOL;

public class OpenPositionOrderPlacedStateHandler implements StateHandler {

    private final Log log;
    private final APIService apiService;
    private final OrderManager orderManager;

    public OpenPositionOrderPlacedStateHandler(APIService apiService,
                                               OrderManager orderManager,
                                               int clientNumber) {
        this.apiService = apiService;
        this.orderManager = orderManager;
        this.log = new Log(clientNumber);
        log.info("OpenPositionOrderPlacedStateHandler initialized");
    }

    @Override
    public void handle(@Nullable Position position, List<Order> openedOrders) {
        orderManager.notifyPositionUpdate(position);
        if (position == null) {
            handleEmptyPosition();
        } else {
            handleNonEmptyPosition();
        }
    }

    private void handleEmptyPosition() {
        log.info("Position is empty in OPEN_ORDER_PLACED state. Checking open position order status...");
        String reason;
        if (orderManager.getOrders().containsKey(OrderManager.OrderType.OPEN)) {
            Order openOrder = queryOpenOrder();
            if (openOrder == null) {
                reason ="Open position order not found.";
            } else {
                switch (openOrder.getStatus()) {
                    case PARTIALLY_FILLED, FILLED -> log.info(String.format("Open position order status: %s. Waiting for position to open.", openOrder.getStatus()));
                    default -> log.warn(String.format("Open position order has unknown status: %s", openOrder.getStatus()));
                }
                return;
            }
        } else {
            reason = "Open position order not found in local orders.";
        }
        resetToEmptyPosition(reason);
    }

    private void handleNonEmptyPosition() {
        log.info("Position is not empty in OPEN_ORDER_PLACED state. Validating open position order...");
        String reason;
        if (orderManager.getOrders().containsKey(OrderManager.OrderType.OPEN)) {
            Order openOrder = queryOpenOrder();
            if (openOrder == null) {
                reason = "Open position order not found.";
            } else {
                switch (openOrder.getStatus()) {
                    case FILLED -> {
                        log.info("Open position order has been FILLED. Switching state to POSITION_OPENED...");
                        orderManager.setState(OrderManager.State.OPEN_ORDER_FILLED);
                        return;
                    }
                    case PARTIALLY_FILLED -> {
                        log.info("Open position order status: PARTIALLY_FILLED. Waiting for full position open.");
                        return;
                    }
                    default ->
                            reason = String.format("Open position order has unexpected status: %s", openOrder.getStatus());
                }
            }
        } else {
            log.warn("Open position order not found in local orders. Assume it is filled already. Switching state to POSITION_OPENED...");
            orderManager.setState(OrderManager.State.OPEN_ORDER_FILLED);
            return;
        }
        resetToEmptyPosition(reason);
    }

    private @Nullable Order queryOpenOrder() {
        Order order = orderManager.getOrders().get(OrderManager.OrderType.OPEN);
        if (order == null) {
            log.warn("Open position order is null.");
            return null;
        }

        log.info(String.format("Querying open position order: %s", order));
        return apiService.queryOrder(SYMBOL, order.getNewClientOrderId()).getResponse();
    }

    private void resetToEmptyPosition(String reason) {
        log.warn(reason);
        log.debug(String.format("Local orders: %s", orderManager.getOrders()));
        orderManager.closePositionAndResetState();
    }
}