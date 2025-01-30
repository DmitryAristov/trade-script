package org.tradebot.service.strategy_state_handlers;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.tradebot.binance.APIService;
import org.tradebot.domain.Order;
import org.tradebot.domain.Position;
import org.tradebot.service.OrderManager;
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
    public void handle(@Nullable Position position, List<Order> openedOrders) {
        if (position == null) {
            handleEmptyPosition();
        } else {
            handleNonEmptyPosition(position);
        }
    }

    private void handleEmptyPosition() {
        log.info("Position is empty in OPEN_ORDER_PLACED state. Checking open position order status...");
        String reason;
        if (orderManager.getOrders().containsKey(OrderType.OPEN)) {
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
        resetToEmptyPosition(null, reason);
    }

    private void handleNonEmptyPosition(@NotNull Position position) {
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
                        orderManager.handleOpenOrderFilled(position);
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
            orderManager.handleOpenOrderFilled(position);
            return;
        }
        resetToEmptyPosition(position, reason);
    }

    private @Nullable Order queryOpenOrder() {
        String orderId = orderManager.getOrders().get(OrderType.OPEN);
        if (orderId == null) {
            log.warn("Open position order ID is null.");
            return null;
        }

        log.info(String.format("Querying open position order: %s", orderId));
        return apiService.queryOrder(symbol, orderId).getResponse();
    }

    private void resetToEmptyPosition(@Nullable Position position, String reason) {
        log.warn(reason);
        log.debug(String.format("Local orders: %s", orderManager.getOrders()));
        orderManager.resetToEmptyPosition(position);
    }
}