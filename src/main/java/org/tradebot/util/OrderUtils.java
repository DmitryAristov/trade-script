package org.tradebot.util;

import org.tradebot.domain.Imbalance;
import org.tradebot.domain.Order;
import org.tradebot.domain.Position;

public class OrderUtils {
    public static final double[] TAKE_PROFIT_THRESHOLDS = new double[]{0.35, 0.75};
    public static final double STOP_LOSS_MULTIPLIER = 0.02;
    public static final String OPEN_POSITION_CLIENT_ID_PREFIX = "position_open_order_";
    public static final String STOP_CLIENT_ID_PREFIX = "stop_";
    public static final String TAKE_CLIENT_ID_PREFIX = "take_";
    public static final String BREAK_EVEN_STOP_CLIENT_ID_PREFIX = "breakeven_stop_";
    private final Log log = new Log();

    public Order createOpen(String symbol,
                                   Imbalance imbalance,
                                   double quantity) {
        Order open = new Order();
        open.setSymbol(symbol);
        open.setType(Order.Type.MARKET);

        switch (imbalance.getType()) {
            case UP -> open.setSide(Order.Side.SELL);
            case DOWN -> open.setSide(Order.Side.BUY);
        }
        open.setQuantity(quantity);
        open.setCreateTime(System.currentTimeMillis());
        open.setNewClientOrderId(OPEN_POSITION_CLIENT_ID_PREFIX + System.currentTimeMillis());
        log.info("Open position order created: " + open.getNewClientOrderId());
        return open;
    }

    public Order createTake(String symbol,
                                   Position position,
                                   double imbalanceSize,
                                   int number) {
        Order take = new Order();
        take.setSymbol(symbol);
        take.setType(Order.Type.LIMIT);
        take.setReduceOnly(true);
        take.setQuantity(Math.abs(position.getPositionAmt()) * 0.5);
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
        take.setNewClientOrderId(TAKE_CLIENT_ID_PREFIX + number + "_" + System.currentTimeMillis());
        log.info(number + " take profit order created: " + take.getNewClientOrderId());
        return take;
    }

    public Order createStop(String symbol, Imbalance imbalance, Position position) {
        Order stop = new Order();
        stop.setSymbol(symbol);
        stop.setType(Order.Type.STOP_MARKET);
        stop.setClosePosition(true);

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
        stop.setNewClientOrderId(STOP_CLIENT_ID_PREFIX + System.currentTimeMillis());
        log.info("Stop loss order created: " + stop.getNewClientOrderId());
        return stop;
    }

    public Order createBreakEvenStop(String symbol, Position position) {
        Order breakEven = new Order();
        breakEven.setSymbol(symbol);
        breakEven.setType(Order.Type.STOP_MARKET);
        breakEven.setStopPrice(position.getBreakEvenPrice());
        breakEven.setClosePosition(true);
        breakEven.setSide(switch (position.getType()) {
            case SHORT -> Order.Side.BUY;
            case LONG -> Order.Side.SELL;
        });
        breakEven.setCreateTime(System.currentTimeMillis());
        breakEven.setNewClientOrderId(BREAK_EVEN_STOP_CLIENT_ID_PREFIX + System.currentTimeMillis());
        log.info("Break-even stop order created: " + breakEven.getNewClientOrderId());
        return breakEven;
    }

    public Order createClosePositionOrder(String symbol, String clientId) {
        Order close = new Order();
        close.setSymbol(symbol);
        close.setType(Order.Type.MARKET);
        close.setReduceOnly(true);
        close.setCreateTime(System.currentTimeMillis());
        close.setNewClientOrderId(clientId);
        log.info("Close position order created: " + close.getNewClientOrderId());
        return close;
    }
}
