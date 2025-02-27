package org.tradebot.strategy_state_handlers;

import org.jetbrains.annotations.Nullable;
import org.tradebot.domain.Order;
import org.tradebot.domain.Position;
import org.tradebot.service.OrderManager;
import org.tradebot.util.Log;

import java.util.List;

public class EmptyPositionStateHandler implements StateHandler {

    private final Log log;
    private final OrderManager orderManager;

    public EmptyPositionStateHandler(OrderManager orderManager, int clientNumber) {
        this.orderManager = orderManager;
        this.log = new Log(clientNumber);
        log.info("EmptyPositionStateHandler initialized.");
    }

    @Override
    public void handle(@Nullable Position position, List<Order> openedOrders) {
        if (position == null) {
            log.info("No active position. System remains in EMPTY_POSITION state.");
        } else {
            log.info(String.format("Position detected: %s. Transitioning to OPEN_ORDER_PLACED state.", position));
            orderManager.setState(OrderManager.State.OPEN_ORDER_PLACED);
        }
    }
}
