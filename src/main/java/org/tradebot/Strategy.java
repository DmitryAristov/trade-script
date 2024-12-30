package org.tradebot;


import org.tradebot.binance.RestAPIService;
import org.tradebot.binance.WebSocketService;
import org.tradebot.domain.Imbalance;
import org.tradebot.domain.Order;
import org.tradebot.domain.Position;
import org.tradebot.listener.ImbalanceStateListener;
import org.tradebot.listener.OrderBookListener;
import org.tradebot.listener.UserDataListener;
import org.tradebot.service.ImbalanceService;
import org.tradebot.util.Log;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.tradebot.TradingBot.LEVERAGE;

public class Strategy implements OrderBookListener, ImbalanceStateListener, UserDataListener {

    public enum State {
        ENTRY_POINT_SEARCH,
        WAIT_POSITION_CLOSED
    }

    private static final double[] TAKE_PROFIT_THRESHOLDS = new double[]{0.35, 0.75};
    private static final double STOP_LOSS_MODIFICATOR = 0.015;
    private static final long POSITION_LIVE_TIME = 4;

    private final RestAPIService apiService;
    private final WebSocketService webSocketService;
    private final Map<String, Order> orders = new HashMap<>();

    private Map<Double, Double> bids = new ConcurrentHashMap<>();
    private Map<Double, Double> asks = new ConcurrentHashMap<>();

    public State state;
    private double availableBalance;

    private ScheduledExecutorService closePositionTimer;

    public Strategy(RestAPIService apiService,
                    WebSocketService webSocketService) {
        this.apiService = apiService;
        this.webSocketService = webSocketService;
        this.availableBalance = apiService.getAccountBalance();

        Log.info(String.format("""
                        strategy parameters:
                            takes modifiers :: %s
                            stop modificator :: %.2f
                            position live time :: %d hours""",
                Arrays.toString(TAKE_PROFIT_THRESHOLDS),
                STOP_LOSS_MODIFICATOR,
                POSITION_LIVE_TIME));
        Log.info("service created");
    }

    @Override
    public void notifyImbalanceStateUpdate(long currentTime, ImbalanceService.State imbalanceState, Imbalance imbalance) {
        switch (imbalanceState) {
            case PROGRESS -> {
                webSocketService.openUserDataStream();
                state = State.ENTRY_POINT_SEARCH;
            }
            case POTENTIAL_END_POINT -> {
                if (state == State.ENTRY_POINT_SEARCH) {
                    openPosition(currentTime, imbalance);
                }
            }
        }
    }

    private void openPosition(long currentTime, Imbalance imbalance) {

        Order order = new Order();
        double price = switch (imbalance.getType()) {
            case UP -> {
                order.setSide(Order.Side.SELL);
                yield Collections.min(asks.keySet());
            }
            case DOWN -> {
                order.setSide(Order.Side.BUY);
                yield Collections.max(bids.keySet());
            }
        };

        double quantity = availableBalance * LEVERAGE / price * 0.99;
        order.setQuantity(quantity);
        order.setCreateTime(currentTime);
        order.setType(Order.Type.MARKET);
        order.setNewClientOrderId("position_open_order");

        createTakeAndStopOrders(imbalance, quantity);

        orders.put("position_open_order", order);
        Log.info(String.format("order created: %s", order));
        apiService.placeOrder(order);
    }

    private void createTakeAndStopOrders(Imbalance imbalance, double quantity) {
        double imbalanceSize = imbalance.size();
        switch (imbalance.getType()) {
            case UP -> {
                for (int i = 0; i < TAKE_PROFIT_THRESHOLDS.length; i++) {
                    Order order = new Order();
                    order.setSide(Order.Side.BUY);
                    order.setType(Order.Type.LIMIT);
                    order.setPrice(imbalance.getEndPrice() - TAKE_PROFIT_THRESHOLDS[i] * imbalanceSize);
                    order.setQuantity(quantity * 0.5);
                    order.setReduceOnly(true);
                    String clientId = String.format("take_%d", i + 1);
                    order.setNewClientOrderId(clientId);
                    orders.put(clientId, order);
                    Log.info(String.format("order created: %s", order));
                }
                Order order = new Order();
                order.setSide(Order.Side.BUY);
                order.setType(Order.Type.STOP_MARKET);
                order.setStopPrice(imbalance.getEndPrice() + imbalanceSize * STOP_LOSS_MODIFICATOR);
                order.setClosePosition(true);
                order.setNewClientOrderId("stop");
                orders.put("stop", order);
                Log.info(String.format("order created: %s", order));
            }
            case DOWN -> {
                for (int i = 0; i < TAKE_PROFIT_THRESHOLDS.length; i++) {
                    Order order = new Order();
                    order.setSide(Order.Side.SELL);
                    order.setType(Order.Type.LIMIT);
                    order.setPrice(imbalance.getEndPrice() + TAKE_PROFIT_THRESHOLDS[i] * imbalanceSize);
                    order.setQuantity(quantity * 0.5);
                    order.setReduceOnly(true);
                    String clientId = String.format("take_%d", i + 1);
                    order.setNewClientOrderId(clientId);
                    orders.put(clientId, order);
                    Log.info(String.format("order created: %s", order));
                }
                Order order = new Order();
                order.setSide(Order.Side.SELL);
                order.setType(Order.Type.STOP_MARKET);
                order.setStopPrice(imbalance.getEndPrice() - imbalanceSize * STOP_LOSS_MODIFICATOR);
                order.setClosePosition(true);
                order.setNewClientOrderId("stop");
                orders.put("stop", order);
                Log.info(String.format("order created: %s", order));
            }
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
            if ("position_open_order".equals(clientId)) {
                orders.remove("position_open_order");
                apiService.placeOrders(orders.values());

                if (closePositionTimer == null || closePositionTimer.isShutdown()) {
                    closePositionTimer = Executors.newScheduledThreadPool(1);
                }
                closePositionTimer.schedule(this::closePositionByTimeout, POSITION_LIVE_TIME, TimeUnit.HOURS);

                state = State.WAIT_POSITION_CLOSED;
                return;
            }
            if ("take_1".equals(clientId)) {
                orders.remove("take_1");
                placeBreakEvenStop();
                return;
            }
            if ("take_2".equals(clientId)) {
                orders.remove("take_2");
                reinitState();
                return;
            }
            if ("stop".equals(clientId)) {
                orders.remove("stop");
                reinitState();
                return;
            }
            if ("timeout_stop".equals(clientId)) {
                reinitState();

                if (closePositionTimer != null && !closePositionTimer.isShutdown()) {
                    closePositionTimer.shutdownNow();
                    closePositionTimer = null;
                }
            }
        }
    }

    private void reinitState() {
        availableBalance = apiService.getAccountBalance();
        webSocketService.closeUserDataStream();
        state = State.ENTRY_POINT_SEARCH;
        Log.info("position closed, reset state to initial");
    }

    private void closePositionByTimeout() {
        Position position = apiService.getOpenPosition();
        if (position == null) {
            throw Log.error("trying to close position by timeout while position is null");
        }

        Order order = new Order();
        order.setType(Order.Type.MARKET);
        order.setClosePosition(true);
        order.setNewClientOrderId("timeout_stop");
        switch (position.getType()) {
            case SHORT -> order.setSide(Order.Side.BUY);
            case LONG -> order.setSide(Order.Side.SELL);
        }
        Log.info(String.format("order created: %s", order));
        apiService.placeOrder(order);
    }

    private void placeBreakEvenStop() {
        Position position = apiService.getOpenPosition();
        if (position == null) {
            throw Log.error("trying to place break even stop order while position is null");
        }

        orders.remove("stop");
        switch (position.getType()) {
            case SHORT -> {
                Order order = new Order();
                order.setSide(Order.Side.BUY);
                order.setType(Order.Type.STOP_MARKET);
                //TODO add fees calculation
                order.setStopPrice(position.getEntryPrice());
                order.setClosePosition(true);
                orders.put("stop", order);
                Log.info(String.format("order created: %s", order));
            }
            case LONG -> {
                Order order = new Order();
                order.setSide(Order.Side.SELL);
                order.setType(Order.Type.STOP_MARKET);
                order.setStopPrice(position.getEntryPrice());
                order.setClosePosition(true);
                orders.put("stop", order);
                Log.info(String.format("order created: %s", order));
            }
        }
        apiService.placeOrder(orders.get("stop"));
    }
}
