package org.tradebot;


import org.tradebot.binance.RestAPIService;
import org.tradebot.domain.*;
import org.tradebot.listener.ImbalanceStateListener;
import org.tradebot.listener.MarketDataListener;
import org.tradebot.listener.OrderBookListener;
import org.tradebot.service.ImbalanceService;
import org.tradebot.util.Log;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.tradebot.TradingBot.LEVERAGE;

public class Strategy implements OrderBookListener, ImbalanceStateListener, MarketDataListener {

    public enum State {
        ENTRY_POINT_SEARCH,
        WAIT_POSITION_CLOSED
    }

    private static final double[] TAKE_PROFIT_THRESHOLDS = new double[]{0.35, 0.75};
    private static final double STOP_LOSS_MODIFICATOR = 0.01;
    private static final long POSITION_LIVE_TIME = 240 * 60_000L;

    private final ImbalanceService imbalanceService;

    private final RestAPIService apiService;
    public State state;

    private Map<Double, Double> bids = new ConcurrentHashMap<>();
    private Map<Double, Double> asks = new ConcurrentHashMap<>();
    double availableBalance;

    public Strategy(ImbalanceService imbalanceService,
                    RestAPIService apiService) {
        this.imbalanceService = imbalanceService;
        this.apiService = apiService;

        availableBalance = apiService.getAccountBalance();

        Log.info(String.format("""
                        strategy parameters:
                            takes count :: %d
                            takes modifiers :: %s
                            stop modificator :: %.2f
                            position live time :: %d minutes""",
                TAKE_PROFIT_THRESHOLDS.length,
                Arrays.toString(TAKE_PROFIT_THRESHOLDS),
                STOP_LOSS_MODIFICATOR,
                POSITION_LIVE_TIME/60_000L));
    }

    @Override
    public void notify(long timestamp, MarketEntry marketEntry) {
        switch (state) {
            case ENTRY_POINT_SEARCH -> {

            }
            case WAIT_POSITION_CLOSED -> {
                // update stop when position partially closed


                // when closed -> update balance for the next trade
                availableBalance = apiService.getAccountBalance();
            }
        }
    }

    @Override
    public void notify(long currentTime, ImbalanceService.State imbalanceState, Imbalance currentImbalance) {
        switch (imbalanceState) {
            case PROGRESS -> state = State.ENTRY_POINT_SEARCH;
            case POTENTIAL_END_POINT -> {
                if (state == State.ENTRY_POINT_SEARCH) {
                    openPosition(currentTime);
                }
            }
        }
    }

    private void openPosition(long currentTime) {

        // leave current thread and start this part async


        boolean placeAdditionalMarketOrder = false;
        Imbalance imbalance = imbalanceService.getImbalance();
        double imbalanceSize = imbalance.size();
        Order order = new Order();
        double availableQuantity = 0., price = 0;
        double stopLossPrice = imbalance.getEndPrice();
        double[] takeProfitPrices = new double[TAKE_PROFIT_THRESHOLDS.length];
        for (int i = 0; i < TAKE_PROFIT_THRESHOLDS.length; i++) {
            switch (imbalance.getType()) {
                case UP -> {
                    order.setSide(Order.Side.SELL);
                    takeProfitPrices[i] = imbalance.getEndPrice() - TAKE_PROFIT_THRESHOLDS[i] * imbalanceSize;
                    stopLossPrice += imbalanceSize * STOP_LOSS_MODIFICATOR;
                    price = asks.keySet().stream().min(Double::compare).orElse(0.0);
                    availableQuantity = asks.get(price);
                }
                case DOWN -> {
                    order.setSide(Order.Side.BUY);
                    takeProfitPrices[i] = imbalance.getEndPrice() + TAKE_PROFIT_THRESHOLDS[i] * imbalanceSize;
                    stopLossPrice -= imbalanceSize * STOP_LOSS_MODIFICATOR;
                    price = bids.keySet().stream().max(Double::compare).orElse(0.0);
                    availableQuantity = bids.get(price);
                }
            }
        }
        order.setPrice(price);
        if (order.getPrice() == 0) {
            Log.debug("asks: " + asks.toString());
            Log.debug("bids: " + bids.toString());
            Log.debug("imbalance" + imbalance);
            Log.debug(new RuntimeException("price is not set"));
        }
        double quantity = availableBalance * LEVERAGE / price * 0.99;
        if (quantity > availableQuantity) {
            order.setQuantity(availableQuantity);
            placeAdditionalMarketOrder = true;
        } else {
            order.setQuantity(quantity);
        }

        order.setCreateTime(currentTime);
        order.setType(Order.Type.LIMIT);
        int orderId = apiService.placeOrder(order);



        if (placeAdditionalMarketOrder) {

        }



        // Проверить открылась позиция или нет.
        // Если не открылась или недоокрылась, то переоткрывать по текущей цене если позволяет имбаланс сервис.
        Position position = new Position();

        // create take profit orders


    }

    private void closeByTimeout(long currentTime, MarketEntry currentEntry, Position position) {
        if (currentTime - position.getOpenTime() > POSITION_LIVE_TIME) {
            Log.debug(String.format("close positions with timeout %d minutes", POSITION_LIVE_TIME / 60_000L));
            position.close(currentTime, currentEntry.average());
        }
    }

    @Override
    public void notify(Map<Double, Double> asks, Map<Double, Double> bids) {
        this.asks = asks;
        this.bids = bids;
    }
}