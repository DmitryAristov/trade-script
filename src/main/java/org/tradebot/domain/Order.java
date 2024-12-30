package org.tradebot.domain;

import org.tradebot.util.TimeFormatter;

import java.io.Serializable;

public class Order implements Serializable {

    public enum Side {
        BUY,
        SELL
    }

    public enum Type {
        MARKET,
        LIMIT,
        STOP_MARKET,
    }

    private Side side;
    private Double quantity;
    private Double price;
    private long createTime;
    private Type type;

    private Double stopPrice;
    private Boolean closePosition;
    private Boolean reduceOnly;
    private String newClientOrderId;

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

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
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

    public String getNewClientOrderId() {
        return newClientOrderId;
    }

    public void setNewClientOrderId(String newClientOrderId) {
        this.newClientOrderId = newClientOrderId;
    }

    @Override
    public String toString() {
        return String.format("""
                        Order
                           side :: %s
                           type :: %s
                           price :: %.2f
                           quantity :: %.2f$
                           createTime :: %s""",
                side,
                type,
                price,
                quantity,
                TimeFormatter.format(createTime));
    }
}

