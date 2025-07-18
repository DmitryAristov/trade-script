package org.tradebot.domain;

public class Position {
    public enum Type {
        LONG,
        SHORT
    }

    private String symbol;
    private double entryPrice;
    private double positionAmt;
    private double breakEvenPrice;

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

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

    public void setBreakEvenPrice(double breakEvenPrice) {
        this.breakEvenPrice = breakEvenPrice;
    }

    public Type getType() {
        if (positionAmt > 0) {
            return Type.LONG;
        } else if (positionAmt < 0) {
            return Type.SHORT;
        } else {
            throw new RuntimeException("Unexpected zero amount");
        }
    }

    @Override
    public String toString() {
        return String.format("""
                        Position
                           entryPrice :: %s
                           positionAmt :: %s
                           breakEvenPrice :: %s
                        """,
                entryPrice, positionAmt, breakEvenPrice);
    }
}
