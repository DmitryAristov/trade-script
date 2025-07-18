package org.tradebot.strategy_state_handlers;

import org.tradebot.domain.Order;
import org.tradebot.domain.Position;
import org.tradebot.service.OrderManager;
import org.tradebot.util.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StrategyStateDispatcher {

    private final Log log;
    private final Map<OrderManager.State, StateHandler> handlers = new HashMap<>();

    public StrategyStateDispatcher(int clientNumber) {
        this.log = new Log(clientNumber);
        log.info("StrategyStateDispatcher initialized.");
    }

    public void registerHandler(OrderManager.State state, StateHandler handler) {
        if (state == null || handler == null) {
            log.warn("Attempted to register a null state or handler.");
            return;
        }
        handlers.put(state, handler);
        log.info(String.format("Handler registered for state: %s (%s)", state, handler.getClass().getSimpleName()));
    }

    public void dispatch(Position position, List<Order> openedOrders, OrderManager.State currentState) {
        StateHandler handler = handlers.get(currentState);
        if (handler != null) {
            log.info(String.format("Handling %s state...", currentState));
            handler.handle(position, openedOrders);
        } else {
            log.warn(String.format("No handler registered for state: %s. Skipping execution.", currentState));
        }
    }
}
