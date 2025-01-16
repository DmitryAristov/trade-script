package org.tradebot.service.strategy_state_handlers;

import org.tradebot.domain.Order;
import org.tradebot.domain.Position;
import org.tradebot.service.OrderManager;
import org.tradebot.service.Strategy;
import org.tradebot.util.Log;

import java.util.List;

public class EmptyPositionStateHandler implements StateHandler {
    private final Log log = new Log();

    private final OrderManager orderManager;

    public EmptyPositionStateHandler(OrderManager orderManager) {
        this.orderManager = orderManager;
        log.info("EmptyPositionStateHandler initialized.");
    }

    @Override
    public void handle(Position position, List<Order> openedOrders) {
        log.info("Handling EMPTY_POSITION state...");
        if (position == null) {
            log.info("No active position. System remains in EMPTY_POSITION state.");
        } else {
            log.info(String.format("Position detected: %s. Transitioning to OPEN_ORDER_PLACED state.", position));
            orderManager.setState(Strategy.State.OPEN_ORDER_PLACED);
        }
    }
}
