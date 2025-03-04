package org.tradebot.service;

import org.tradebot.domain.*;
import org.tradebot.listener.ImbalanceStateCallback;
import org.tradebot.listener.OrderBookCallback;
import org.tradebot.listener.UserWebSocketCallback;
import org.tradebot.listener.MarketDataWebSocketCallback;
import org.tradebot.util.Log;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.tradebot.util.Settings.*;

public class Strategy implements OrderBookCallback, ImbalanceStateCallback, MarketDataWebSocketCallback, UserWebSocketCallback {

    private final Log log;
    private final TaskManager taskManager;

    private final OrderManager orderManager;

    private final AtomicBoolean marketDataWS = new AtomicBoolean(false);
    private final AtomicBoolean userDataWS = new AtomicBoolean(false);
    private final AtomicBoolean webSocketReady = new AtomicBoolean(false);

    public Strategy(OrderManager orderManager,
                    int clientNumber) {
        this.taskManager = TaskManager.getInstance(clientNumber);
        this.orderManager = orderManager;
        this.log = new Log(clientNumber);
    }

    @Override
    public void notifyImbalanceStateUpdate(long currentTime, MarketEntry currentEntry, ImbalanceService.State imbalanceState, Imbalance imbalance) {
        log.info(String.format("Received imbalance state update: %s", imbalanceState), currentTime);

        if (Objects.requireNonNull(imbalanceState) != ImbalanceService.State.POTENTIAL_END_POINT) {
            log.info("Nothing to update in strategy.");
            return;
        }

        if (imbalance == null) {
            log.info("Trying to place open order with null imbalance. Skipping...");
            return;
        }

        if (!webSocketReady.get()) {
            log.info("Trying to open position when websocket is not ready. Skipping...");
            return;
        }

        orderManager.placeOpenOrder(imbalance, currentEntry.average());
    }

    @Override
    public void notifyMarketDataWSStateChanged(boolean ready) {
        if (marketDataWS.compareAndSet(!ready, ready)) {
            updateWebSocketState(ready, userDataWS.get());
        }
    }

    @Override
    public void notifyUserDataWSChanged(boolean ready) {
        if (userDataWS.compareAndSet(!ready, ready)) {
            updateWebSocketState(marketDataWS.get(), ready);
        }
    }

    private void updateWebSocketState(boolean wsReady, boolean userWsReady) {
        boolean ready = wsReady && userWsReady;

        if (webSocketReady.get() != ready) {
            if (ready) {
                log.info("Switching to WebSocket mode...");
                taskManager.cancel(CHECK_ORDERS_API_MODE_TASK_KEY);
                orderManager.checkOrdersAPI();
                webSocketReady.set(true);
            } else {
                log.info("Switching to API-based order checks period to 1 second...");
                webSocketReady.set(false);
                taskManager.scheduleAtFixedRate(CHECK_ORDERS_API_MODE_TASK_KEY, orderManager::checkOrdersAPI, 2, 2, TimeUnit.SECONDS);
            }
        }
    }

    @Override
    public void notifyOrderBookUpdate(Map<Double, Double> asks, Map<Double, Double> bids) {  }

    public void logAll() {
        try {
            log.debug(String.format("""
                            Strategy state:
                                Symbol: %s
                                Leverage: %d
                                Web Socket summary: %s
                                Market Data WS state: %s
                                User Data WS state: %s
                            """,
                    SYMBOL, LEVERAGE, webSocketReady.get(), marketDataWS.get(), userDataWS.get()));
        } catch (Exception e) {
            log.warn("Failed to write", e);
        }
    }
}
