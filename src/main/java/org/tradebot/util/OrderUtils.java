package org.tradebot.util;

import org.tradebot.domain.Imbalance;
import org.tradebot.domain.Order;
import org.tradebot.domain.Position;

import static org.tradebot.service.Strategy.STOP_LOSS_MULTIPLIER;
import static org.tradebot.service.Strategy.TAKE_PROFIT_THRESHOLDS;

public class OrderUtils {
    public static final String OPEN_POSITION_CLIENT_ID = "position_open_order";
    public static final String TAKE_CLIENT_ID_PREFIX = "take_";
    public static final String STOP_CLIENT_ID = "stop";
    public static final String BREAK_EVEN_STOP_CLIENT_ID = "breakeven_stop";
    public static final String TIMEOUT_STOP_CLIENT_ID = "timeout_stop";
    public static final String FORCE_STOP_CLIENT_ID_PREFIX = "force_stop_";
    public static final String STOP_CLIENT_ID_PREFIX = "stop_";

    public static Order createOpen(String symbol,
                                   Imbalance imbalance,
                                   double quantity) {
        Order open = new Order();
        open.setSymbol(symbol);
        open.setType(Order.Type.MARKET);

        switch (imbalance.getType()) {
            case UP -> open.setSide(Order.Side.SELL);
            case DOWN -> open.setSide(Order.Side.BUY);
        };
        open.setQuantity(quantity);
        open.setNewClientOrderId(OPEN_POSITION_CLIENT_ID);
        open.setCreateTime(System.currentTimeMillis());
        Log.info("open position market order :: " + open);
        return open;
    }

    public static Order createTake(String symbol,
                                   Position position,
                                   double imbalanceSize,
                                   int number) {
        Order take = new Order();
        take.setSymbol(symbol);
        take.setType(Order.Type.LIMIT);
        take.setReduceOnly(true);
        take.setQuantity(position.getPositionAmt() * 0.5);
        take.setNewClientOrderId(TAKE_CLIENT_ID_PREFIX + number);
        take.setTimeInForce(Order.TimeInForce.GTC);

        switch (position.getType()) {
            case SHORT -> {
                take.setSide(Order.Side.BUY);
                take.setPrice(position.getEntryPrice() - TAKE_PROFIT_THRESHOLDS[number] * imbalanceSize);
            }
            case LONG -> {
                take.setSide(Order.Side.SELL);
                take.setPrice(position.getEntryPrice() + TAKE_PROFIT_THRESHOLDS[number] * imbalanceSize);
            }
        }
        take.setCreateTime(System.currentTimeMillis());
        Log.info(number + " take profit limit order :: " + take);
        return take;
    }

    public static Order createStop(String symbol, Imbalance imbalance, Position position) {
        Order stop = new Order();
        stop.setSymbol(symbol);
        stop.setType(Order.Type.STOP_MARKET);
        stop.setClosePosition(true);
        stop.setNewClientOrderId(STOP_CLIENT_ID);

        switch (position.getType()) {
            case SHORT -> {
                stop.setSide(Order.Side.BUY);
                stop.setStopPrice(Math.max(position.getEntryPrice(), imbalance.getEndPrice()) + imbalance.size() * STOP_LOSS_MULTIPLIER);
            }
            case LONG -> {
                stop.setSide(Order.Side.SELL);
                stop.setStopPrice(Math.min(position.getEntryPrice(), imbalance.getEndPrice()) - imbalance.size() * STOP_LOSS_MULTIPLIER);
            }
        }
        stop.setCreateTime(System.currentTimeMillis());
        Log.info("stop loss order :: " + stop);
        return stop;
    }

    public static Order createForceStop(String symbol, Position position) {
        Order stop = new Order();
        stop.setSymbol(symbol);
        stop.setType(Order.Type.MARKET);
        stop.setSide(switch (position.getType()) {
            case SHORT -> Order.Side.BUY;
            case LONG -> Order.Side.SELL;
        });
        stop.setQuantity(position.getPositionAmt());
        stop.setCreateTime(System.currentTimeMillis());
        Log.info("immediately close position order :: " + stop);
        return stop;
    }

    public static Order createBreakevenStop(String symbol, Position position) {
        Order breakeven = new Order();
        breakeven.setSymbol(symbol);
        breakeven.setType(Order.Type.STOP_MARKET);
        //TODO: add fees calculation
        breakeven.setStopPrice(position.getEntryPrice());
        breakeven.setClosePosition(true);
        breakeven.setNewClientOrderId(BREAK_EVEN_STOP_CLIENT_ID);
        breakeven.setSide(switch (position.getType()) {
            case SHORT -> Order.Side.BUY;
            case LONG -> Order.Side.SELL;
        });
        breakeven.setCreateTime(System.currentTimeMillis());
        Log.info("breakeven stop order :: " + breakeven);
        return breakeven;
    }
}
