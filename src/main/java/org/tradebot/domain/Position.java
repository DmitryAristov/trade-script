package org.tradebot.domain;

import org.tradebot.util.Log;

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

    public double getPositionAmt() {
        return positionAmt;
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
            throw Log.error("zero position amount");
        }
    }

    @Override
    public String toString() {
        return String.format("""
                        Order
                           entryPrice :: %s
                           positionAmt :: %s""",
                entryPrice,
                positionAmt);
    }
}
