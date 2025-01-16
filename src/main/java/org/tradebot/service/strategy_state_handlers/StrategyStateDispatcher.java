package org.tradebot.service.strategy_state_handlers;

import org.tradebot.domain.Order;
import org.tradebot.domain.Position;
import org.tradebot.service.OrderManager;
import org.tradebot.service.Strategy;
import org.tradebot.util.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StrategyStateDispatcher {
    private final Log log = new Log();

    private final Map<Strategy.State, StateHandler> handlers = new HashMap<>();
    private final OrderManager orderManager;

    public StrategyStateDispatcher(OrderManager orderManager) {
        this.orderManager = orderManager;
        log.info("StrategyStateDispatcher initialized.");
    }

    public void registerHandler(Strategy.State state, StateHandler handler) {
        if (state == null || handler == null) {
            log.warn("Attempted to register a null state or handler.");
            return;
        }
        handlers.put(state, handler);
        log.info(String.format("Handler registered for state: %s (%s)", state, handler.getClass().getSimpleName()));
    }

    public void dispatch(Position position, List<Order> openedOrders) {
        Strategy.State currentState = orderManager.getState();
        log.info(String.format("Dispatching handler for current state: %s", currentState));

        StateHandler handler = handlers.get(currentState);
        if (handler != null) {
            handler.handle(position, openedOrders);
        } else {
            log.warn(String.format("No handler registered for state: %s. Skipping execution.", currentState));
        }
    }
}
