package org.tradebot.domain;

import org.tradebot.service.TradingBot;
import org.tradebot.util.TimeFormatter;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.tradebot.service.TradingBot.DEFAULT_PRECISION;

public class Order {

    public enum Side {
        BUY,
        SELL
    }

    public enum Type {
        MARKET,
        LIMIT,
        STOP_MARKET,
        STOP,
        TAKE_PROFIT_MARKET,
        TAKE_PROFIT,
        LIMIT_MAKER,
        TRAILING_STOP_MARKET
    }

    public enum TimeInForce {
        GTC,
        IOC,
        FOK
    }

    public enum Status {
        NEW,
        FILLED,
        PARTIALLY_FILLED,
        CANCELED,
        EXPIRED,
        REJECTED,
        PENDING_CANCEL,
        NEW_INSURANCE,
        NEW_ADL,
        EXPIRED_IN_MATCH
    }

    private Long id;
    private String symbol;
    private Side side;
    private Type type;
    private BigDecimal price;
    private BigDecimal quantity;
    private Long createTime;

    private BigDecimal stopPrice;
    private Boolean closePosition;
    private Boolean reduceOnly;
    private String newClientOrderId;
    private TimeInForce timeInForce;
    private Status status;
    
    public Order() { }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

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
        this.price = getBigDecimalPrice(price);
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(double quantity) {
        this.quantity = getBigDecimalQuantity(quantity);
    }

    public BigDecimal getStopPrice() {
        return stopPrice;
    }

    public void setStopPrice(double stopPrice) {
        this.stopPrice = getBigDecimalPrice(stopPrice);
    }

    public static BigDecimal getBigDecimalPrice(double value) {
        Precision precision = DEFAULT_PRECISION;
        if (TradingBot.getInstance() != null && TradingBot.getInstance().getPrecision() != null) {
            precision = TradingBot.getInstance().getPrecision();
        }
        return BigDecimal.valueOf(value).setScale(precision.price(), RoundingMode.HALF_UP);
    }

    public static BigDecimal getBigDecimalQuantity(double quantity) {
        Precision precision = DEFAULT_PRECISION;
        if (TradingBot.getInstance() != null && TradingBot.getInstance().getPrecision() != null) {
            precision = TradingBot.getInstance().getPrecision();
        }
        return BigDecimal.valueOf(quantity).setScale(precision.quantity(), RoundingMode.DOWN);
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }

    public Long getCreateTime() {
        return createTime;
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

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return String.format("""
                        Order
                           id :: %s
                           symbol :: %s
                           side :: %s
                           type :: %s
                           price :: %s
                           quantity :: %s
                           stopPrice :: %s
                           closePosition :: %s
                           reduceOnly :: %s
                           newClientOrderId :: %s
                           timeInForce :: %s
                           status :: %s
                           createTime :: %s (%d)
                        """,
                id,
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
                status,
                createTime == null ? 0 : TimeFormatter.format(createTime),
                createTime);
    }
}

