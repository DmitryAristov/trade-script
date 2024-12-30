package org.tradebot.domain;

public class Position {
    public enum Type {
        LONG,
        SHORT
    }

    private double entryPrice;
    private double positionAmt;

    public double getEntryPrice() {
        return entryPrice;
    }

    public void setEntryPrice(double entryPrice) {
        this.entryPrice = entryPrice;
    }

    public void setPositionAmt(double positionAmt) {
        this.positionAmt = positionAmt;
    }

    public Type getType() {
        if (positionAmt > 0) {
            return Type.LONG;
        } else if (positionAmt < 0) {
            return Type.SHORT;
        } else {
            throw new RuntimeException("zero position amount");
        }
    }
}


