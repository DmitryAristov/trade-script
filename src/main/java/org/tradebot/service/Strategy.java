package org.tradebot.service;

import org.tradebot.binance.RestAPIService;
import org.tradebot.binance.WebSocketService;
import org.tradebot.domain.Imbalance;
import org.tradebot.domain.Order;
import org.tradebot.domain.Position;
import org.tradebot.domain.WorkingType;
import org.tradebot.listener.ImbalanceStateListener;
import org.tradebot.listener.OrderBookListener;
import org.tradebot.listener.UserDataListener;
import org.tradebot.listener.WebSocketListener;
import org.tradebot.util.Log;
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

public class Strategy implements OrderBookListener, ImbalanceStateListener, UserDataListener, WebSocketListener {

    private static final double[] TAKE_PROFIT_THRESHOLDS = new double[]{0.35, 0.75};
    private static final double STOP_LOSS_MODIFICATOR = 0.015;
    private static final long POSITION_LIVE_TIME = 4;

    private final RestAPIService apiService;
    private final WebSocketService webSocketService;
    private final TaskManager taskManager;
    private final String symbol;
    private final int leverage;

    private Map<Double, Double> bids = new ConcurrentHashMap<>();
    private Map<Double, Double> asks = new ConcurrentHashMap<>();
    protected final Map<String, Order> orders = new HashMap<>();

    protected boolean positionOpened = false;
    protected WorkingType workingType = WorkingType.API;

    public Strategy(String symbol,
                    int leverage,
                    RestAPIService apiService,
                    WebSocketService webSocketService,
                    TaskManager taskManager) {
        this.symbol = symbol;
        this.leverage = leverage;
        this.apiService = apiService;
        this.webSocketService = webSocketService;
        this.taskManager = taskManager;

        //TODO: support positionOpened = true if was is opened before initialization
        Log.info(String.format("""
                        strategy parameters:
                            takes modifiers :: %s
                            stop modificator :: %.2f
                            position live time :: %d hours""",
                Arrays.toString(TAKE_PROFIT_THRESHOLDS),
                STOP_LOSS_MODIFICATOR,
                POSITION_LIVE_TIME));
    }

    @Override
    public void notifyImbalanceStateUpdate(long currentTime, ImbalanceService.State imbalanceState, Imbalance imbalance) {
        switch (imbalanceState) {
            case PROGRESS -> webSocketService.updateUserDataStream(true);
            case POTENTIAL_END_POINT -> {
                if (!positionOpened) {
                    openPosition(currentTime, imbalance);
                }
            }
        }
    }

    private void openPosition(long currentTime, Imbalance imbalance) {
        Order openPosition = new Order();
        openPosition.setSymbol(symbol);
        openPosition.setType(Order.Type.MARKET);
        double price = switch (imbalance.getType()) {
            case UP -> {
                openPosition.setSide(Order.Side.SELL);
                yield Collections.min(asks.keySet());
            }
            case DOWN -> {
                openPosition.setSide(Order.Side.BUY);
                yield Collections.max(bids.keySet());
            }
        };
        Log.info("price from order book: " + price);

        //TODO: issue with not full quantity
        double quantity = apiService.getAccountBalance() * leverage / price * 0.95;
        openPosition.setQuantity(quantity);
        openPosition.setCreateTime(currentTime);
        openPosition.setNewClientOrderId("position_open_order");

        placeStop(imbalance);

        Log.info(String.format("open position market order created: %s", openPosition));
        openPosition = apiService.placeOrder(openPosition);
        orders.put("position_open_order", openPosition);

        createTakes(imbalance, quantity);
    }

    private void placeStop(Imbalance imbalance) {
        Order stop = new Order();
        stop.setSymbol(symbol);
        stop.setType(Order.Type.STOP_MARKET);
        stop.setCreateTime(System.currentTimeMillis());
        stop.setClosePosition(true);
        stop.setNewClientOrderId("stop");

        double imbalanceSize = imbalance.size();
        switch (imbalance.getType()) {
            case UP -> {
                stop.setSide(Order.Side.BUY);
                stop.setStopPrice(imbalance.getEndPrice() + imbalanceSize * STOP_LOSS_MODIFICATOR);
            }
            case DOWN -> {
                stop.setSide(Order.Side.SELL);
                stop.setStopPrice(imbalance.getEndPrice() - imbalanceSize * STOP_LOSS_MODIFICATOR);
            }
        }
        stop = apiService.placeOrder(stop);
        orders.put("stop", stop);
        Log.info(String.format("stop market order placed: %s", stop));
    }

    private void createTakes(Imbalance imbalance, double quantity) {
        Order take1 = new Order();
        take1.setSymbol(symbol);
        take1.setType(Order.Type.LIMIT);
        take1.setCreateTime(System.currentTimeMillis());
        take1.setReduceOnly(true);
        take1.setQuantity(quantity * 0.5);
        take1.setNewClientOrderId("take_1");
        take1.setTimeInForce(Order.TimeInForce.GTC);

        Order take2 = new Order();
        take2.setSymbol(symbol);
        take2.setCreateTime(System.currentTimeMillis());
        take2.setType(Order.Type.LIMIT);
        take2.setReduceOnly(true);
        take2.setQuantity(quantity * 0.5);
        take2.setNewClientOrderId("take_2");
        take2.setTimeInForce(Order.TimeInForce.GTC);

        double imbalanceSize = imbalance.size();
        switch (imbalance.getType()) {
            case UP -> {
                take1.setSide(Order.Side.BUY);
                take2.setSide(Order.Side.BUY);
                take1.setPrice(imbalance.getEndPrice() - TAKE_PROFIT_THRESHOLDS[0] * imbalanceSize);
                take2.setPrice(imbalance.getEndPrice() - TAKE_PROFIT_THRESHOLDS[1] * imbalanceSize);
            }
            case DOWN -> {
                take1.setSide(Order.Side.SELL);
                take2.setSide(Order.Side.SELL);
                take1.setPrice(imbalance.getEndPrice() + TAKE_PROFIT_THRESHOLDS[0] * imbalanceSize);
                take2.setPrice(imbalance.getEndPrice() + TAKE_PROFIT_THRESHOLDS[1] * imbalanceSize);
            }
        }
        orders.put("take_1", take1);
        orders.put("take_2", take2);
        Log.info(String.format("take profit limit order created: %s", take1));
        Log.info(String.format("take profit limit order created: %s", take1));

        if (workingType == WorkingType.API) {
            Log.error("websocket connection lost, scheduling place take profit orders with timeout");
            taskManager.create("manual_open_take_profits", () -> {
                if (positionOpened) {
                    return;
                }

                if (apiService.queryOrder(orders.get("position_open_order")).getStatus() == Order.Status.FILLED) {
                    orders.replace("take_1", apiService.placeOrder(orders.get("take_1")));
                    orders.replace("take_2", apiService.placeOrder(orders.get("take_2")));
                    positionOpened = true;
                    taskManager.create("check_orders_api", this::updateOrdersState, TaskManager.Type.PERIOD, 5, 5, TimeUnit.SECONDS);
                    taskManager.stop("manual_open_take_profits");
                }
            }, TaskManager.Type.PERIOD, 0, 2, TimeUnit.SECONDS);
        }
    }

    @Override
    public void notifyOrderBookUpdate(Map<Double, Double> asks, Map<Double, Double> bids) {
        this.asks = asks;
        this.bids = bids;
    }

    @Override
    public void notifyOrderUpdate(String clientId, String status) {
        Log.info(String.format("new status %s of order %s", clientId, status));
        if ("FILLED".equals(status)) {
            if (orders.containsKey(clientId)) {
                orders.get(clientId).setStatus(Order.Status.FILLED);
                if ("position_open_order".equals(clientId)) {
                    Log.info("open position market order filled");

                    taskManager.create("close_position", this::closePositionByTimeout, TaskManager.Type.ONCE, POSITION_LIVE_TIME, -1, TimeUnit.HOURS);
                    Log.info("position will be closed", System.currentTimeMillis() + POSITION_LIVE_TIME * 60 * 60 * 1000L);

                    orders.replace("take_1", apiService.placeOrder(orders.get("take_1")));
                    orders.replace("take_2", apiService.placeOrder(orders.get("take_2")));
                    positionOpened = true;

                    return;
                }
                if (List.of("take_2", "stop", "breakeven_stop", "timeout_stop").contains(clientId)) {
                    Log.info(String.format("%s order filled, position is closed", clientId));
                    reinitState();

                    return;
                }
                if ("take_1".equals(clientId)) {
                    Log.info("first take limit order filled");
                    placeBreakEvenStop();

                    return;
                }
            }
        }
    }

    private void reinitState() {
        Log.info("reset state to initial");
        webSocketService.updateUserDataStream(false);
        taskManager.stop("close_position");
        taskManager.stop("check_orders_api");
        taskManager.stop("manual_open_take_profits");
        orders.clear();

        positionOpened = false;
    }

    protected void closePositionByTimeout() {
        Log.info(String.format("closing position by timeout at %s", TimeFormatter.format(Instant.now())));
        Position position = apiService.getOpenPosition(symbol);
        if (position == null) {
            throw Log.error("trying to close position by timeout while position is null");
        }

        Order timeout = new Order();
        timeout.setSymbol(symbol);
        timeout.setType(Order.Type.MARKET);
        timeout.setCreateTime(System.currentTimeMillis());
        timeout.setClosePosition(true);
        timeout.setNewClientOrderId("timeout_stop");
        timeout.setSide(switch (position.getType()) {
            case SHORT -> Order.Side.BUY;
            case LONG -> Order.Side.SELL;
        });

        Log.info(String.format("timeout stop order created: %s", timeout));
        orders.put("timeout_stop", apiService.placeOrder(timeout));
    }

    protected void placeBreakEvenStop() {
        Log.info("set break even stop");
        Position position = apiService.getOpenPosition(symbol);
        if (position == null) {
            throw Log.error("trying to place break even stop when position is null");
        }

        if (orders.containsKey("stop")) {
            apiService.cancelOrder(orders.get("stop"));
            orders.get("stop").setStatus(Order.Status.CANCELED);
        }

        Order breakeven = new Order();
        breakeven.setSymbol(symbol);
        breakeven.setType(Order.Type.STOP_MARKET);
        breakeven.setCreateTime(System.currentTimeMillis());
        //TODO: add fees calculation
        breakeven.setStopPrice(position.getEntryPrice());
        breakeven.setClosePosition(true);
        breakeven.setNewClientOrderId("breakeven_stop");
        breakeven.setSide(switch (position.getType()) {
            case SHORT -> Order.Side.BUY;
            case LONG -> Order.Side.SELL;
        });

        Log.info(String.format("breakeven stop market order created: %s", breakeven));
        orders.put("breakeven_stop", apiService.placeOrder(breakeven));
    }

    @Override
    public void notifyWebsocketStateChanged(boolean ready) {
        if (!ready) {
            workingType = WorkingType.API;
            if (positionOpened) {
                taskManager.create("check_orders_api", this::updateOrdersState, TaskManager.Type.PERIOD, 5, 5, TimeUnit.SECONDS);
            }
        } else {
            workingType = WorkingType.WEBSOCKET;
            taskManager.stop("check_orders_api");
        }
    }

    private void updateOrdersState() {
        Log.debug("actualizing order statuses");
        orders.values().forEach(local -> {
            Order actual = apiService.getOpenOrder(local);
            if (actual.getStatus() != local.getStatus() && actual.getStatus() == Order.Status.FILLED) {
                if (List.of("take_2", "stop", "breakeven_stop", "timeout_stop").contains(actual.getNewClientOrderId())) {
                    Log.info(String.format("%s order filled, position is closed", actual.getNewClientOrderId()));
                    local.setStatus(Order.Status.FILLED);
                    reinitState();
                } else if ("take_1".equals(local.getNewClientOrderId())) {
                    local.setStatus(Order.Status.FILLED);
                    placeBreakEvenStop();
                }
            }
        });
    }

    public void logAll() {
        Log.debug(String.format("symbol: %s", symbol));
        Log.debug(String.format("leverage: %d", leverage));
        Log.debug(String.format("orders: %s", orders));
        Log.debug(String.format("bids: %s", bids));
        Log.debug(String.format("asks: %s", asks));
        Log.debug(String.format("positionOpened: %s", positionOpened));
    }
}
