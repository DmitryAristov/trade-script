package org.tradebot.util;

import org.jetbrains.annotations.NotNull;
import org.tradebot.domain.Imbalance;
import org.tradebot.domain.Order;
import org.tradebot.domain.Position;

import static org.tradebot.util.Settings.*;

public class OrderUtils {
    private final Log log;

    public OrderUtils(int clientNumber) {
        this.log = new Log(clientNumber);
    }

    public Order createOpen(String symbol,
                            @NotNull Imbalance imbalance,
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
        open.setNewClientOrderId(OPEN_POSITION_CLIENT_ID_KEY);
        log.info("Open position order created: " + open);
        return open;
    }

    public Order createFirstTake(String symbol, @NotNull Position position, double imbalanceSize) {
        Order take = createTake(symbol);
        take.setQuantity(Math.abs(position.getPositionAmt()) * 0.5);
        setPriceAndSide(take, 0, position, imbalanceSize);
        take.setNewClientOrderId(TAKE_CLIENT_ID_PREFIX + 0);

        log.info("First take profit order created: " + take);
        return take;
    }

    public Order createSecondTake(String symbol, @NotNull Position position, double imbalanceSize) {
        Order take = createTake(symbol);
        double fullPosition = Math.abs(position.getPositionAmt());
        take.setQuantity(fullPosition - Order.getBigDecimalQuantity(fullPosition * 0.5).doubleValue());
        setPriceAndSide(take, 1, position, imbalanceSize);
        take.setNewClientOrderId(TAKE_CLIENT_ID_PREFIX + 1);

        log.info("Second take profit order created: " + take);
        return take;
    }

    private void setPriceAndSide(Order take, int number, Position position, double imbalanceSize) {
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

    }

    private Order createTake(String symbol) {
        Order take = new Order();
        take.setSymbol(symbol);
        take.setType(Order.Type.LIMIT);
        take.setReduceOnly(true);
        take.setTimeInForce(Order.TimeInForce.GTC);
        take.setCreateTime(System.currentTimeMillis());
        return take;
    }

    public Order createStop(String symbol,
                            @NotNull Imbalance imbalance,
                            @NotNull Position position) {
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
        stop.setNewClientOrderId(STOP_CLIENT_ID_KEY);
        log.info("Stop loss order created: " + stop);
        return stop;
    }

    public Order createBreakEvenStop(String symbol,
                                     @NotNull Position position) {
        Order breakEven = new Order();
        breakEven.setSymbol(symbol);
        breakEven.setType(Order.Type.STOP_MARKET);
        //TODO
        breakEven.setStopPrice(position.getEntryPrice());
        breakEven.setClosePosition(true);
        breakEven.setSide(switch (position.getType()) {
            case SHORT -> Order.Side.BUY;
            case LONG -> Order.Side.SELL;
        });
        breakEven.setCreateTime(System.currentTimeMillis());
        breakEven.setNewClientOrderId(BREAK_EVEN_STOP_CLIENT_ID_KEY);
        log.info("Break-even stop order created: " + breakEven);
        return breakEven;
    }

    public Order createClosePosition(String symbol,
                                     String clientId,
                                     Position position) {
        Order close = new Order();
        close.setSymbol(symbol);
        close.setType(Order.Type.MARKET);
        close.setReduceOnly(true);
        close.setCreateTime(System.currentTimeMillis());
        close.setNewClientOrderId(clientId);

        close.setQuantity(Math.abs(position.getPositionAmt()));
        close.setSide(switch (position.getType()) {
            case SHORT -> Order.Side.BUY;
            case LONG -> Order.Side.SELL;
        });
        log.info("Close position order created: " + close);
        return close;
    }
}
