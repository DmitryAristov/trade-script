package org.tradebot.service;

import org.tradebot.binance.RestAPIService;
import org.tradebot.domain.Imbalance;
import org.tradebot.domain.Order;
import org.tradebot.domain.Position;
import org.tradebot.domain.WorkingType;
import org.tradebot.listener.ImbalanceStateListener;
import org.tradebot.listener.OrderBookListener;
import org.tradebot.listener.UserDataListener;
import org.tradebot.listener.WebSocketListener;
import org.tradebot.util.Log;
import org.tradebot.util.OrderUtils;
import org.tradebot.util.TaskManager;
import org.tradebot.util.TimeFormatter;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class Strategy implements OrderBookListener, ImbalanceStateListener, UserDataListener, WebSocketListener {

    public static final double[] TAKE_PROFIT_THRESHOLDS = new double[]{0.35, 0.75};
    public static final double STOP_LOSS_MULTIPLIER = 0.015;
    public static final long POSITION_LIVE_TIME = 4;

    private final RestAPIService apiService;
    private final TaskManager taskManager;
    private final String symbol;
    private final int leverage;

    private Map<Double, Double> bids = new ConcurrentHashMap<>();
    private Map<Double, Double> asks = new ConcurrentHashMap<>();
    protected final Map<String, Order> orders = new HashMap<>();

    protected final AtomicReference<Position> position = new AtomicReference<>(null);
    protected Imbalance currentImbalance = null;
    protected WorkingType workingType = WorkingType.API;

    public Strategy(String symbol,
                    int leverage,
                    RestAPIService apiService,
                    TaskManager taskManager) {
        this.symbol = symbol;
        this.leverage = leverage;
        this.apiService = apiService;
        this.taskManager = taskManager;

        Log.info(String.format("""
                        strategy parameters:
                            takes modifiers :: %s
                            stop price multiplier :: %.2f
                            position live time :: %d hours""",
                Arrays.toString(TAKE_PROFIT_THRESHOLDS),
                STOP_LOSS_MULTIPLIER,
                POSITION_LIVE_TIME));
    }

    @Override
    public void notifyImbalanceStateUpdate(long currentTime, ImbalanceService.State imbalanceState, Imbalance imbalance) {
        if (imbalanceState == ImbalanceService.State.POTENTIAL_END_POINT) {
            if (position.get() == null) {
                openPosition(currentTime, imbalance);
            }
        }
    }

    private void openPosition(long currentTime, Imbalance imbalance) {
        Log.info("open position", currentTime);
        this.currentImbalance = imbalance;

        double price = switch (currentImbalance.getType()) {
            case UP -> Collections.min(asks.keySet());
            case DOWN -> Collections.max(bids.keySet());
        };
        Log.info("price from order book: " + price);
        double quantity = apiService.getAccountBalance() * leverage / price * 0.95; //TODO: issue with not full quantity
        if (price <= 0 || quantity <= 0) {
            throw Log.error("Invalid price or quantity: price=" + price + ", quantity=" + quantity);
        }
        Order open = OrderUtils.createOpen(symbol, currentImbalance, quantity);

        // if websocket is not available create periodic job to update order statuses
        if (workingType == WorkingType.API) {
            taskManager.create("check_orders_api_mode", this::updateOrdersState, TaskManager.Type.PERIOD, 0, 1, TimeUnit.SECONDS);
        }

        // open position
        open = apiService.placeOrder(open);
        orders.put("position_open_order", open);
        Log.info("open position order placed");

        // if websocket is not available place closing orders here after position is opened
        if (workingType == WorkingType.API) {
            Log.warn("websocket is off, place closing orders manually");
            taskManager.create("place_orders_api_mode", () -> {
                if (apiService.queryOrder(orders.get("position_open_order")).getStatus() == Order.Status.FILLED) {
                    resyncPositionState();
                    placeClosingOrders();
                    taskManager.stop("place_orders_api_mode");
                }
            }, TaskManager.Type.PERIOD, 0, 1, TimeUnit.SECONDS);
        }
    }

    @Override
    public void notifyOrderUpdate(String clientId, String status) {
        Log.info(String.format("order '%s' has new status: %s", clientId, status));
        if ("FILLED".equals(status)) {
            if (orders.containsKey(clientId)) {
                orders.get(clientId).setStatus(Order.Status.FILLED);
                if ("position_open_order".equals(clientId)) {
                    resyncPositionState();
                    placeClosingOrders();

                    taskManager.create("autoclose_position_timer", this::closePositionWithTimeout, TaskManager.Type.ONCE, POSITION_LIVE_TIME, -1, TimeUnit.HOURS);
                    Log.info("position will be closed", System.currentTimeMillis() + POSITION_LIVE_TIME * 60 * 60 * 1000L);

                    return;
                }
                if (List.of("take_1", "stop", "breakeven_stop", "timeout_stop").contains(clientId)) {
                    Log.info(String.format("%s order filled, position is closed", clientId));
                    reinitState();

                    return;
                }
                if ("take_0".equals(clientId)) {
                    Log.info("first take limit order filled");
                    placeBreakEvenStop();
                    return;
                }
                if (clientId.contains("force_stop_")) {
                    throw Log.error("force closed due to error when placing closing orders");
                }
            }
        }
    }

    protected void updateOrdersState() {
        Log.info("actualizing order statuses");
        if (orders.size() == 1 && orders.containsKey("position_open_order") &&
                apiService.queryOrder(orders.get("position_open_order")).getStatus() == Order.Status.FILLED
        ) {
            Log.warn("take and stop orders are not created, but position is opened. try to create..");
            resyncPositionState();
            placeClosingOrders();
            return;
        }

        resyncOrdersState();
        orders.values().stream()
                .filter(order -> order.getStatus() == Order.Status.FILLED)
                .forEach(order -> {
                    if (List.of("take_1", "stop", "breakeven_stop", "timeout_stop").contains(order.getNewClientOrderId())) {
                        Log.info(String.format("%s order filled, position is closed", order.getNewClientOrderId()));
                        reinitState();
                    } else if ("take_0".equals(order.getNewClientOrderId())) {
                        placeBreakEvenStop();
                    } else if (order.getNewClientOrderId().contains("force_stop_")) {
                        throw Log.error("force closed due to error when placing closing orders");
                    }
                });
    }

    @Override
    public void notifyWebsocketStateChanged(boolean ready) {
        if (!ready) {
            workingType = WorkingType.API;
            if (position.get() != null) {
                taskManager.create("check_orders_api_mode", this::updateOrdersState, TaskManager.Type.PERIOD, 0, 1, TimeUnit.SECONDS);
            }
        } else {
            workingType = WorkingType.WEBSOCKET;
            resyncPositionState();
            resyncOrdersState();
            taskManager.stop("check_orders_api_mode");
        }
    }

    protected void placeClosingOrders() {
        if (position.get() == null)
            throw Log.error("trying to place closing orders when have no opened position");

        int retries = 3;
        while (retries-- > 0) {
            try {
                Order stop = OrderUtils.createStop(symbol, currentImbalance.size(), position.get());
                if (!orders.containsKey("stop") || orders.get("stop").getStatus() == null) {
                    orders.put("stop", apiService.placeOrder(stop));
                    Log.info("stop order placed: " + stop);
                }

                for (int i = 0; i < TAKE_PROFIT_THRESHOLDS.length; i++) {
                    Order take = OrderUtils.createTake(symbol, position.get(), currentImbalance.size(), i);
                    if (!orders.containsKey(take.getNewClientOrderId()) || orders.get(take.getNewClientOrderId()).getStatus() == null) {
                        orders.put(take.getNewClientOrderId(), apiService.placeOrder(take));
                        Log.info("take profit order placed: " + take);
                    }
                }

                return;
            } catch (Exception e) {
                Log.warn("Retry attempt failed: " + (3 - retries));
                Log.warn(e);
                try {
                    TimeUnit.SECONDS.sleep(1);
                } catch (InterruptedException ignored) {  }
            }
        }

        Log.warn("failed to place closing orders after retries.");

        Order forceStop = OrderUtils.createForceStop(symbol, position.get());
        forceStop.setNewClientOrderId("force_stop_" + System.currentTimeMillis());
        orders.put(forceStop.getNewClientOrderId(), apiService.placeOrder(forceStop));

        Log.info("force stop order placed: " + forceStop);
    }

    protected void placeBreakEvenStop() {
        resyncPositionState();
        if (position.get() == null) {
            Log.warn("no position to set breakeven stop");
            return;
        }

        Order breakeven = OrderUtils.createBreakevenStop(symbol, position.get());
        orders.put("breakeven_stop", apiService.placeOrder(breakeven));
        orders.remove("take_0"); // not need any more to track the first take order status

        Log.info("breakeven stop order placed: " + breakeven);
    }

    protected void closePositionWithTimeout() {
        resyncPositionState();
        if (position.get() == null) {
            Log.warn("no position to close at timeout.");
            return;
        }

        Log.info(String.format("closing position by timeout at %s", TimeFormatter.format(Instant.now())));
        Order timeout = OrderUtils.createForceStop(symbol, position.get());
        timeout.setNewClientOrderId("timeout_stop");

        orders.put("timeout_stop", apiService.placeOrder(timeout));
        Log.info("timeout order placed: " + timeout);
    }

    private void reinitState() {
        Log.info("reset state to initial");
        taskManager.stop("autoclose_position_timer");
        taskManager.stop("check_orders_api_mode");
        taskManager.stop("place_orders_api_mode");
        orders.clear();

        position.set(null);
    }

    private void resyncPositionState() {
        position.set(apiService.getOpenPosition(symbol));
        Log.info("Resynced position state: " + position.get());
    }

    private void resyncOrdersState() {
        orders.replaceAll((_, order) -> apiService.queryOrder(order));
        Log.info(String.format("Orders updated: %s", orders));
    }

    @Override
    public void notifyOrderBookUpdate(Map<Double, Double> asks, Map<Double, Double> bids) {
        this.asks = asks;
        this.bids = bids;
    }

    public void logAll() {
        Log.debug(String.format("symbol: %s", symbol));
        Log.debug(String.format("leverage: %d", leverage));
        Log.debug(String.format("orders: %s", orders));
        Log.debug(String.format("bids: %s", bids));
        Log.debug(String.format("asks: %s", asks));
        Log.debug(String.format("position: %s", position.get()));
        Log.debug(String.format("currentImbalance: %s", currentImbalance));
        Log.debug(String.format("workingType: %s", workingType));
    }
}
