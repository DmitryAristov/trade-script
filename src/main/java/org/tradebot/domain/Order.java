package org.tradebot.domain;

import org.tradebot.util.TimeFormatter;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.tradebot.service.TradingBot.precision;

public class Order {

    public enum Side {
        BUY,
        SELL
    }

    public enum Type {
        MARKET,
        LIMIT,
        STOP_MARKET,
    }

    public enum TimeInForce {
        GTC,
        IOC,
        FOK
    }

    private String symbol;
    private Side side;
    private Type type;
    private BigDecimal price;
    private BigDecimal quantity;
    private long createTime;

    private BigDecimal stopPrice;
    private Boolean closePosition;
    private Boolean reduceOnly;
    private String newClientOrderId;
    private TimeInForce timeInForce;
    
    public Order() { }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

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

    public BigDecimal getPrice() {
        return this.price;
    }

    public void setPrice(double price) {
        this.price = BigDecimal.valueOf(price).setScale(precision.price(), RoundingMode.HALF_UP);
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(double quantity) {
        this.quantity = BigDecimal.valueOf(quantity).setScale(precision.quantity(), RoundingMode.DOWN);
    }

    public BigDecimal getStopPrice() {
        return stopPrice;
    }

    public void setStopPrice(double stopPrice) {
        this.stopPrice = BigDecimal.valueOf(stopPrice).setScale(precision.price(), RoundingMode.HALF_UP);
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

    public TimeInForce getTimeInForce() {
        return timeInForce;
    }

    public void setTimeInForce(TimeInForce timeInForce) {
        this.timeInForce = timeInForce;
    }

    @Override
    public String toString() {
        return String.format("""
                        Order
                           symbol :: %s
                           side :: %s
                           type :: %s
                           price :: %.2f$
                           quantity :: %.2f
                           stopPrice :: %.2f$
                           closePosition :: %s
                           reduceOnly :: %s
                           newClientOrderId :: %s
                           timeInForce :: %s
                           createTime :: %s""",
                symbol,
                side,
                type,
                price,
                quantity,
                stopPrice,
                closePosition,
                reduceOnly,
                newClientOrderId,
                timeInForce,
                TimeFormatter.format(createTime));
    }
}

