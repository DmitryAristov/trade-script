package org.tradebot.service;

import org.tradebot.binance.RestAPIService;
import org.tradebot.domain.Imbalance;
import org.tradebot.domain.Order;
import org.tradebot.domain.Position;
import org.tradebot.listener.ImbalanceStateCallback;
import org.tradebot.listener.OrderBookCallback;
import org.tradebot.listener.UserDataCallback;
import org.tradebot.listener.WebSocketCallback;
import org.tradebot.util.Log;
import org.tradebot.util.OrderUtils;
import org.tradebot.util.TaskManager;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.tradebot.util.OrderUtils.*;

public class Strategy implements OrderBookCallback, ImbalanceStateCallback, UserDataCallback, WebSocketCallback {

    public static final double[] TAKE_PROFIT_THRESHOLDS = new double[]{0.35, 0.75};
    public static final double STOP_LOSS_MULTIPLIER = 0.015;
    public static final long POSITION_LIVE_TIME = 2;
    public static final long TIMEOUT_FORCE_STOP_CREATION = 2000L;

    private final RestAPIService apiService;
    private final TaskManager taskManager;
    private final String symbol;
    private final int leverage;
    private Map<Double, Double> bids = new ConcurrentSkipListMap<>(Collections.reverseOrder());
    private Map<Double, Double> asks = new ConcurrentSkipListMap<>();
    protected final Map<String, Order> openedOrders = new ConcurrentHashMap<>();

    protected final AtomicReference<Position> position = new AtomicReference<>(null);
    protected Imbalance currentImbalance = null;
    protected AtomicBoolean websocketState = new AtomicBoolean(false);

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
            if (websocketState.get() && position.get() == null) {
                openPosition(currentTime, imbalance);
            }
        }
    }

    private void openPosition(long currentTime, Imbalance imbalance) {
        Log.info("open position", currentTime);
        currentImbalance = imbalance;
        double price = getPrice(imbalance);
        double quantity = apiService.getAccountBalance() * leverage / price;
        Order open = OrderUtils.createOpen(symbol, imbalance, quantity);
        placeOrder(open);

        taskManager.create("check_stop_order_exists", () -> {
            if (!openedOrders.containsKey(STOP_CLIENT_ID)) {
                placeClosingOrdersAndScheduleCloseTask(imbalance);
            }
        }, TaskManager.Type.DELAYED, TIMEOUT_FORCE_STOP_CREATION, -1, TimeUnit.MILLISECONDS);
    }

    private double getPrice(Imbalance imbalance) {
        double price = switch (imbalance.getType()) {
            case UP -> Collections.min(asks.keySet());
            case DOWN -> Collections.max(bids.keySet());
        };
        Log.info("price from order book :: " + price);
        return price;
    }

    protected void placeClosingOrdersAndScheduleCloseTask(Imbalance imbalance) {
        try {
            if (openedOrders.remove(OPEN_POSITION_CLIENT_ID) == null)
                return;
            if (!websocketState.get())
                updatePositionAPI();
            if (position.get() == null) {
                Log.warn("trying to place order when position is null");
                reinitState();
                return;
            }
            if (placeStopOrder(imbalance))
                return;
            if (placeTakeOrders(imbalance))
                return;

            taskManager.create("autoclose_position_timer", () -> closePosition(TIMEOUT_STOP_CLIENT_ID),
                    TaskManager.Type.DELAYED, POSITION_LIVE_TIME, -1, TimeUnit.MINUTES);
        } catch (Exception e) {
            Log.warn("failed to place closing orders");
            Log.warn(e);
            closePosition(FORCE_STOP_CLIENT_ID_PREFIX + System.currentTimeMillis());
        }
    }

    private boolean placeStopOrder(Imbalance imbalance) {
        try {
            placeOrder(OrderUtils.createStop(symbol, imbalance, position.get()));
        } catch (Exception e) {
            Log.warn(e);
            if (e.getMessage().contains("Order would immediately trigger")) {
                closePosition(STOP_CLIENT_ID_PREFIX + System.currentTimeMillis());
                return true;
            }
            throw e;
        }
        return false;
    }

    private boolean placeTakeOrders(Imbalance imbalance) {
        List<Order> takes = new ArrayList<>();
        for (int i = 0; i < TAKE_PROFIT_THRESHOLDS.length; i++)
            takes.add(OrderUtils.createTake(symbol, position.get(), imbalance.size(), i));

        try {
            placeOrders(takes);
        } catch (Exception e) {
            Log.warn(e);
            if (e.getMessage().contains("ReduceOnly Order is rejected")) {
                Log.warn("trying to place order when position is null");
                reinitState();
                return true;
            }
            if (e.getMessage().contains("Order would immediately trigger")) {
                closePosition(STOP_CLIENT_ID_PREFIX + System.currentTimeMillis());
                return true;
            }
            throw Log.error(e);
        }
        return false;
    }

    @Override
    public void notifyOrderUpdate(String clientId, String status) {
        Log.info(String.format("order '%s' has new status :: %s", clientId, status));
        if ("FILLED".equals(status)) {
            if (openedOrders.containsKey(clientId)) {
                openedOrders.get(clientId).setStatus(Order.Status.FILLED);
                handleOrderStatusUpdate(clientId);
            }
        }
    }

    protected void updateOrdersState() {
        updatePositionAPI();
        if (position.get() == null && openedOrders.isEmpty()) {
            Log.info("position is null, exit");
            return;
        }

        openedOrders.replaceAll((_, order) -> apiService.queryOrder(order));
        Log.info("orders updated :: " + openedOrders);
        List<Order> filledOrders = openedOrders.values().stream()
                .filter(order -> order.getStatus() == Order.Status.FILLED)
                .toList();

        if (filledOrders.isEmpty()) {
            if (position.get() == null) {
                Log.warn("position was closed by user, or bot missed close event");
                reinitState();
            } else {
                Log.info("nothing to update, exit");
            }
            return;
        }

        filledOrders.forEach(order -> handleOrderStatusUpdate(order.getNewClientOrderId()));
    }

    private void handleOrderStatusUpdate(String clientId) {
        switch (clientId) {
            case OPEN_POSITION_CLIENT_ID -> placeClosingOrdersAndScheduleCloseTask(currentImbalance);
            case TAKE_CLIENT_ID_PREFIX + 1, STOP_CLIENT_ID, BREAK_EVEN_STOP_CLIENT_ID, TIMEOUT_STOP_CLIENT_ID -> {
                Log.info(String.format("%s order filled, position is closed", clientId));
                reinitState();
            }
            case TAKE_CLIENT_ID_PREFIX + 0 -> placeBreakEvenStop();
            case null, default -> {
                if (clientId != null)
                    if (clientId.contains(FORCE_STOP_CLIENT_ID_PREFIX)) {
                        TradingBot.exit("force stop order has been filled");
                    } else if (clientId.contains(STOP_CLIENT_ID_PREFIX)) {
                        reinitState();
                    }
            }
        }
    }

    protected void placeBreakEvenStop() {
        Log.info("first take limit order filled");
        if (!websocketState.get())
            updatePositionAPI();
        if (position.get() == null) {
            Log.warn("position is null");
            reinitState();
            return;
        }
        cancelMarketStop();
        try {
            placeOrder(OrderUtils.createBreakevenStop(symbol, position.get()));
            openedOrders.remove(TAKE_CLIENT_ID_PREFIX + 0);
        } catch (Exception e) {
            Log.warn(e);
            if (e.getMessage().contains("Order would immediately trigger")) {
                closePosition(STOP_CLIENT_ID_PREFIX + System.currentTimeMillis());
                return;
            }
            throw e;
        }
    }

    private void cancelMarketStop() {
        if (openedOrders.containsKey(STOP_CLIENT_ID) && openedOrders.get(STOP_CLIENT_ID).getStatus() == Order.Status.NEW) {
            apiService.cancelOrder(openedOrders.get(STOP_CLIENT_ID));
            openedOrders.remove(STOP_CLIENT_ID);
        } else {
            Log.warn("nothing to cancel");
        }
    }

    protected void closePosition(String clientId) {
        Log.info("closing position..");
        updatePositionAPI();
        if (position.get() == null) {
            Log.warn("trying to place order when position is null");
            return;
        }
        Order forceStop = OrderUtils.createForceStop(symbol, position.get());
        forceStop.setNewClientOrderId(clientId);
        placeOrder(forceStop);
    }

    private void reinitState() {
        Log.info("reset state to initial");
        taskManager.stop("autoclose_position_timer");
        openedOrders.clear();
        apiService.cancelAllOpenOrders(symbol);
        closePosition(STOP_CLIENT_ID_PREFIX + System.currentTimeMillis());
    }

    private void placeOrders(Collection<Order> orders) {
        Collection<Order> list = orders.stream()
                .filter(order -> !this.openedOrders.containsKey(order.getNewClientOrderId()))
                .toList();

        apiService.placeBatchOrders(list)
                .forEach(createdOrder -> this.openedOrders.put(createdOrder.getNewClientOrderId(), createdOrder));
    }

    private void placeOrder(Order order) {
        if (openedOrders.containsKey(order.getNewClientOrderId())) {
            Log.warn("order '" + order.getNewClientOrderId() + "' already tried to place or placed");
            return;
        }

        order = apiService.placeOrder(order);
        openedOrders.put(order.getNewClientOrderId(), order);
        Log.info("order placed :: " + order);
    }

    private void updatePositionAPI() {
        position.set(apiService.getOpenPosition(symbol));
        Log.info("resynced position state: " + position.get());
    }

    @Override
    public void notifyPositionUpdate(Position position) {
        Log.info("position updated :: " + position);
        this.position.set(position);
    }

    @Override
    public void notifyWebsocketStateChanged(boolean ready) {
        if (!ready) {
            websocketState.set(false);
            taskManager.create("check_orders_api_mode", this::updateOrdersState, TaskManager.Type.PERIOD, 0, 10, TimeUnit.SECONDS);
        } else {
            websocketState.set(true);
            taskManager.stop("check_orders_api_mode");
            updateOrdersState();
        }
    }

    @Override
    public void notifyOrderBookUpdate(Map<Double, Double> asks, Map<Double, Double> bids) {
        this.asks = asks;
        this.bids = bids;
    }

    public void logAll() {
        Log.debug(String.format("""
                        symbol :: %s
                        leverage :: %d
                        orders :: %s
                        bids :: %s
                        asks :: %s
                        position :: %s
                        current imbalance :: %s
                        websocket state :: %s
                        """,
                symbol,
                leverage,
                openedOrders,
                bids,
                asks,
                position.get(),
                currentImbalance,
                websocketState
        ));
    }
}
