package org.tradebot.domain;

import org.tradebot.util.TimeFormatter;

import java.io.Serializable;

public class Order implements Serializable {
    public static final double LIMIT_ORDER_TRADE_FEE = 0.00002;
    public static final double MARKET_ORDER_TRADE_FEE = 0.00005;

    public enum Side {
        BUY,
        SELL
    }

    public enum Type {
        MARKET,
        LIMIT,
        STOP,
        TAKE_PROFIT,
        STOP_MARKET,
        TAKE_PROFIT_MARKET
    }

    private Side side;
    private Double quantity;
    private Double price;
    private long createTime;
    private Type type;

    private Double stopPrice;
    private Boolean closePosition;
    private Boolean reduceOnly;

    public Order() { }

    public Side getSide() {
        return side;
    }

    public void setSide(Side side) {
        this.side = side;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public Double getPrice() {
        return this.price;
    }

    public void setPrice(double price) {
        this.price = price;
    }

    public Double getQuantity() {
        return quantity;
    }

    public void setQuantity(double quantity) {
        this.quantity = quantity;
    }

    public Double getStopPrice() {
        return stopPrice;
    }

    public void setStopPrice(double stopPrice) {
        this.stopPrice = stopPrice;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public double getTradeFee() {
        return switch (type) {
            case MARKET, STOP_MARKET, TAKE_PROFIT_MARKET -> MARKET_ORDER_TRADE_FEE;
            case LIMIT, STOP, TAKE_PROFIT -> LIMIT_ORDER_TRADE_FEE;
        };
    }

    public Boolean isClosePosition() {
        return closePosition;
    }

    public void setClosePosition(boolean closePosition) {
        this.closePosition = closePosition;
    }

    public Boolean isReduceOnly() {
        return reduceOnly;
    }

    public void setReduceOnly(boolean reduceOnly) {
        this.reduceOnly = reduceOnly;
    }

    @Override
    public String toString() {
        return String.format("""
                        Order
                           type :: %s
                           quantity :: %.2f$
                           stopLossPrice :: %.2f$
                           createTime :: %s""",
                side,
                quantity,
                stopLossPrice,
                TimeFormatter.format(createTime));
    }
}

