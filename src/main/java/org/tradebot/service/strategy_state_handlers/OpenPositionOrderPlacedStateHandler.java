package org.tradebot.service.strategy_state_handlers;

import org.tradebot.binance.APIService;
import org.tradebot.domain.Order;
import org.tradebot.domain.Position;
import org.tradebot.service.OrderManager;
import org.tradebot.service.Strategy;
import org.tradebot.service.OrderManager.OrderType;
import org.tradebot.util.Log;

import java.util.List;

public class OpenPositionOrderPlacedStateHandler implements StateHandler {
    private final Log log = new Log();

    private final APIService apiService;
    private final OrderManager orderManager;
    private final String symbol;

    public OpenPositionOrderPlacedStateHandler(APIService apiService,
                                               OrderManager orderManager,
                                               String symbol) {
        this.apiService = apiService;
        this.orderManager = orderManager;
        this.symbol = symbol;
        log.info("OpenPositionOrderPlacedStateHandler initialized");
    }

    @Override
    public void handle(Position position, List<Order> openedOrders) {
        log.info("Handling OPEN_ORDER_PLACED state...");
        if (position == null) {
            handleEmptyPosition();
        } else {
            handleNonEmptyPosition(position);
        }
    }

    private void handleEmptyPosition() {
        log.info("Position is empty in OPEN_ORDER_PLACED state. Checking open position order status...");
        if (orderManager.getOrders().containsKey(OrderType.OPEN)) {
            Order openOrder = queryOpenOrder();
            if (openOrder == null) {
                resetToEmptyPosition("Open position order not found.");
                return;
            }
            switch (openOrder.getStatus()) {
                case PARTIALLY_FILLED, FILLED -> log.info(String.format("Open position order status: %s. Waiting for position to open.", openOrder.getStatus()));
                default -> log.warn(String.format("Open position order has unknown status: %s", openOrder.getStatus()));
            }
        } else {
            resetToEmptyPosition("Open position order not found in local orders.");
        }
    }

    private void handleNonEmptyPosition(Position position) {
        log.info("Position is not empty in OPEN_ORDER_PLACED state. Validating open position order...");
        String reason;
        if (orderManager.getOrders().containsKey(OrderType.OPEN)) {
            Order openOrder = queryOpenOrder();
            if (openOrder == null) {
                reason = "Open position order not found.";
            } else {
                switch (openOrder.getStatus()) {
                    case FILLED -> {
                        log.info("Open position order has been FILLED. Switching state to POSITION_OPENED...");
                        orderManager.getOrders().remove(OrderType.OPEN);
                        orderManager.setState(Strategy.State.POSITION_OPENED);
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
            reason = "Open position order not found in local orders.";
        }
        resetToEmptyPosition(reason, position);
    }

    private Order queryOpenOrder() {
        String orderId = orderManager.getOrders().get(OrderType.OPEN);
        if (orderId == null) {
            log.warn("Open position order ID is null.");
            return null;
        }

        log.info(String.format("Querying open position order: %s", orderId));
        return apiService.queryOrder(symbol, orderId).getResponse();
    }

    private void resetToEmptyPosition(String reason) {
        log.warn(reason);
        log.debug(String.format("Local orders: %s", orderManager.getOrders()));
        orderManager.resetToEmptyPosition();
    }

    private void resetToEmptyPosition(String reason, Position position) {
        log.warn(reason);
        log.debug(String.format("Local orders: %s", orderManager.getOrders()));
        orderManager.closePosition();
        orderManager.resetToEmptyPosition();
    }
}