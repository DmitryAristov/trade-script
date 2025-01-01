package org.tradebot.service;

import org.tradebot.binance.RestAPIService;
import org.tradebot.binance.WebSocketService;
import org.tradebot.domain.Imbalance;
import org.tradebot.domain.Order;
import org.tradebot.domain.Position;
import org.tradebot.listener.ImbalanceStateListener;
import org.tradebot.listener.OrderBookListener;
import org.tradebot.listener.UserDataListener;
import org.tradebot.util.Log;
import org.tradebot.util.TimeFormatter;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Strategy implements OrderBookListener, ImbalanceStateListener, UserDataListener {

    private static final double[] TAKE_PROFIT_THRESHOLDS = new double[]{0.35, 0.75};
    private static final double STOP_LOSS_MODIFICATOR = 0.015;
    private static final long POSITION_LIVE_TIME = 4;

    private final RestAPIService apiService;
    private final WebSocketService webSocketService;
    private final String symbol;
    private final int leverage;

    private Map<Double, Double> bids = new ConcurrentHashMap<>();
    private Map<Double, Double> asks = new ConcurrentHashMap<>();
    private double availableBalance;

    protected final Map<String, Order> orders = new HashMap<>();
    protected boolean positionOpened = false;
    protected ScheduledExecutorService closePositionTimer;

    public Strategy(String symbol,
                    int leverage,
                    RestAPIService apiService,
                    WebSocketService webSocketService) {
        this.symbol = symbol;
        this.leverage = leverage;
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
    }

    @Override
    public void notifyImbalanceStateUpdate(long currentTime, ImbalanceService.State imbalanceState, Imbalance imbalance) {
        switch (imbalanceState) {
            case PROGRESS -> webSocketService.openUserDataStream();
            case POTENTIAL_END_POINT -> {
                if (!positionOpened) {
                    openPosition(currentTime, imbalance);
                }
            }
        }
    }

    private void openPosition(long currentTime, Imbalance imbalance) {
        Order order = new Order();
        order.setSymbol(symbol);
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
        Log.info("price from order book: " + price);
        Log.info("account balance: " + availableBalance);

        double quantity = availableBalance * leverage / price * 0.99;
        order.setQuantity(quantity);
        order.setCreateTime(currentTime);
        order.setType(Order.Type.MARKET);
        order.setNewClientOrderId("position_open_order");

        createTakeAndStopOrders(imbalance, quantity);

        orders.put("position_open_order", order);
        Log.info(String.format("open position market order created: %s", order));

        apiService.placeOrder(order);
    }

    private void createTakeAndStopOrders(Imbalance imbalance, double quantity) {
        double imbalanceSize = imbalance.size();
        switch (imbalance.getType()) {
            case UP -> {
                for (int i = 0; i < TAKE_PROFIT_THRESHOLDS.length; i++) {
                    Order order = new Order();
                    order.setSymbol(symbol);
                    order.setSide(Order.Side.BUY);
                    order.setType(Order.Type.LIMIT);
                    order.setPrice(imbalance.getEndPrice() - TAKE_PROFIT_THRESHOLDS[i] * imbalanceSize);
                    order.setQuantity(quantity * 0.5);
                    order.setReduceOnly(true);
                    String clientId = String.format("take_%d", i + 1);
                    order.setNewClientOrderId(clientId);
                    order.setTimeInForce(Order.TimeInForce.GTC);

                    orders.put(clientId, order);
                    Log.info(String.format("take profit limit order created: %s", order));
                }
                Order order = new Order();
                order.setSymbol(symbol);
                order.setSide(Order.Side.BUY);
                order.setType(Order.Type.STOP_MARKET);
                order.setStopPrice(imbalance.getEndPrice() + imbalanceSize * STOP_LOSS_MODIFICATOR);
                order.setClosePosition(true);
                order.setNewClientOrderId("stop");

                orders.put("stop", order);
                Log.info(String.format("stop market order created: %s", order));
            }
            case DOWN -> {
                for (int i = 0; i < TAKE_PROFIT_THRESHOLDS.length; i++) {
                    Order order = new Order();
                    order.setSymbol(symbol);
                    order.setSide(Order.Side.SELL);
                    order.setType(Order.Type.LIMIT);
                    order.setPrice(imbalance.getEndPrice() + TAKE_PROFIT_THRESHOLDS[i] * imbalanceSize);
                    order.setQuantity(quantity * 0.5);
                    order.setReduceOnly(true);
                    String clientId = String.format("take_%d", i + 1);
                    order.setNewClientOrderId(clientId);
                    order.setTimeInForce(Order.TimeInForce.GTC);

                    orders.put(clientId, order);
                    Log.info(String.format("take profit limit order created: %s", order));
                }
                Order order = new Order();
                order.setSymbol(symbol);
                order.setSide(Order.Side.SELL);
                order.setType(Order.Type.STOP_MARKET);
                order.setStopPrice(imbalance.getEndPrice() - imbalanceSize * STOP_LOSS_MODIFICATOR);
                order.setClosePosition(true);
                order.setNewClientOrderId("stop");

                orders.put("stop", order);
                Log.info(String.format("stop market order created: %s", order));
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
                Log.info("open position market order filled");
                orders.remove("position_open_order");
                apiService.placeOrders(orders.values());

                if (closePositionTimer == null || closePositionTimer.isShutdown()) {
                    closePositionTimer = Executors.newScheduledThreadPool(1);
                }
                Log.info("close position timer created", System.currentTimeMillis());
                Log.info("position will be closed ", System.currentTimeMillis() + POSITION_LIVE_TIME * 60 * 60 * 1000L);
                closePositionTimer.schedule(this::closePositionByTimeout, POSITION_LIVE_TIME, TimeUnit.HOURS);

                positionOpened = true;
                return;
            }
            if ("take_1".equals(clientId)) {
                Log.info("first take limit order filled");
                orders.remove("take_1");
                placeBreakEvenStop();
                return;
            }
            if (List.of("take_2", "stop", "breakeven_stop", "timeout_stop").contains(clientId)) {
                Log.info(String.format("%s order filled, position is closed", clientId));
                orders.remove(clientId);
                reinitState();
            }
        }
    }

    private void reinitState() {
        availableBalance = apiService.getAccountBalance();
        Log.info(String.format("reset state to initial. Updated balance: %.2f",availableBalance));
        webSocketService.closeUserDataStream();
        stopClosePositionTimer();
        orders.clear();
        positionOpened = false;
    }

    protected void closePositionByTimeout() {
        Log.info(String.format("closing position by timeout at %s", TimeFormatter.format(Instant.now())));
        Position position = apiService.getOpenPosition(symbol);
        if (position == null) {
            throw Log.error("trying to close position by timeout while position is null");
        }

        Order order = new Order();
        order.setSymbol(symbol);
        order.setType(Order.Type.MARKET);
        order.setClosePosition(true);
        order.setNewClientOrderId("timeout_stop");
        order.setSide(switch (position.getType()) {
            case SHORT -> Order.Side.BUY;
            case LONG -> Order.Side.SELL;
        });

        orders.put("timeout_stop", order);
        Log.info(String.format("timeout stop order created: %s", order));

        apiService.placeOrder(order);
    }

    protected void placeBreakEvenStop() {
        Log.info("set break even stop");
        Position position = apiService.getOpenPosition(symbol);
        if (position == null) {
            throw Log.error("trying to place break even stop when position is null");
        }

        orders.remove("stop");
        Order order = new Order();
        order.setSymbol(symbol);
        order.setType(Order.Type.STOP_MARKET);

        //TODO add fees calculation
        order.setStopPrice(position.getEntryPrice());
        order.setClosePosition(true);
        order.setNewClientOrderId("breakeven_stop");
        order.setSide(switch (position.getType()) {
            case SHORT -> Order.Side.BUY;
            case LONG -> Order.Side.SELL;
        });

        orders.put("breakeven_stop", order);
        Log.info(String.format("stop market order modified: %s", order));

        apiService.placeOrder(orders.get("breakeven_stop"));
    }

    public void stopClosePositionTimer() {
        if (closePositionTimer != null && !closePositionTimer.isShutdown()) {
            closePositionTimer.shutdownNow();
            closePositionTimer = null;
            Log.info("close position timer off");
        }
    }

    public void logAll() {
        Log.debug(String.format("symbol: %s", symbol));
        Log.debug(String.format("leverage: %d", leverage));
        Log.debug(String.format("orders: %s", orders));
        Log.debug(String.format("bids: %s", bids));
        Log.debug(String.format("asks: %s", asks));
        Log.debug(String.format("positionOpened: %s", positionOpened));
        Log.debug(String.format("availableBalance: %s", availableBalance));
        Log.debug(String.format("closePositionTimer isShutdown: %s", closePositionTimer.isShutdown()));
        Log.debug(String.format("closePositionTimer isTerminated: %s", closePositionTimer.isTerminated()));
    }
}
