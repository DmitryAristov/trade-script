package org.tradebot.service.strategy_state_handlers;

import org.jetbrains.annotations.Nullable;
import org.tradebot.domain.Order;
import org.tradebot.domain.Position;

import java.util.List;

public interface StateHandler {

    void handle(@Nullable Position position, List<Order> openedOrders);
}
