package org.tradebot.util;

import org.tradebot.domain.Imbalance;
import org.tradebot.domain.Order;
import org.tradebot.domain.Position;

import static org.tradebot.service.Strategy.STOP_LOSS_MULTIPLIER;
import static org.tradebot.service.Strategy.TAKE_PROFIT_THRESHOLDS;

public class OrderUtils {

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
        open.setNewClientOrderId("position_open_order");
        open.setCreateTime(System.currentTimeMillis());
        Log.info(String.format("open position market order created: %s", open));
        return open;
    }

    public static Order createTake(String symbol,
                                   Position position,
                                   double imbalanceSize,
                                   int number) {
        Order take = new Order();
        take.setSymbol(symbol);
        take.setCreateTime(System.currentTimeMillis());
        take.setType(Order.Type.LIMIT);
        take.setReduceOnly(true);
        take.setQuantity(position.getPositionAmt() * 0.5);
        take.setNewClientOrderId(String.format("take_%d", number));
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
        Log.info(String.format("%d take profit limit order created: %s", number, take));
        return take;
    }

    public static Order createStop(String symbol, double imbalanceSize, Position position) {
        Order stop = new Order();
        stop.setSymbol(symbol);
        stop.setType(Order.Type.STOP_MARKET);
        stop.setCreateTime(System.currentTimeMillis());
        stop.setClosePosition(true);
        stop.setNewClientOrderId("stop");

        switch (position.getType()) {
            case SHORT -> {
                stop.setSide(Order.Side.BUY);
                stop.setStopPrice(position.getEntryPrice() + imbalanceSize * STOP_LOSS_MULTIPLIER);
            }
            case LONG -> {
                stop.setSide(Order.Side.SELL);
                stop.setStopPrice(position.getEntryPrice() - imbalanceSize * STOP_LOSS_MULTIPLIER);
            }
        }
        stop.setCreateTime(System.currentTimeMillis());
        Log.info(String.format("stop market order placed: %s", stop));
        return stop;
    }

    public static Order createForceStop(String symbol, Position position) {
        Order timeout = new Order();
        timeout.setSymbol(symbol);
        timeout.setType(Order.Type.MARKET);
        timeout.setClosePosition(true);
        timeout.setSide(switch (position.getType()) {
            case SHORT -> Order.Side.BUY;
            case LONG -> Order.Side.SELL;
        });
        timeout.setCreateTime(System.currentTimeMillis());
        Log.info(String.format("force close position market order created: %s", timeout));
        return timeout;
    }

    public static Order createBreakevenStop(String symbol, Position position) {
        Order breakeven = new Order();
        breakeven.setSymbol(symbol);
        breakeven.setType(Order.Type.STOP_MARKET);
        //TODO: add fees calculation
        breakeven.setStopPrice(position.getEntryPrice());
        breakeven.setClosePosition(true);
        breakeven.setNewClientOrderId("breakeven_stop");
        breakeven.setSide(switch (position.getType()) {
            case SHORT -> Order.Side.BUY;
            case LONG -> Order.Side.SELL;
        });
        breakeven.setCreateTime(System.currentTimeMillis());
        Log.info(String.format("breakeven stop market order created: %s", breakeven));
        return breakeven;
    }
}
