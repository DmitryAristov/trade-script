package org.tradebot.service.strategy_state_handlers;

import org.tradebot.domain.Order;
import org.tradebot.domain.Position;

import java.util.List;

public interface StateHandler {

    void handle(Position position, List<Order> openedOrders);
}
