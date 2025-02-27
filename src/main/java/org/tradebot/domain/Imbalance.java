package org.tradebot.domain;


import org.tradebot.util.TimeFormatter;

import java.io.Serializable;

public class Imbalance implements Serializable {

    public enum Type { UP, DOWN }

    private final Type type;
    private final long startTime;
    private final double startPrice;
    private double endPrice;
    private long endTime;
    private double computedDuration;

    public Imbalance(long startTime, double startPrice, long endTime, double endPrice, Type type) {
        this.startTime = startTime;
        this.endTime = endTime;
        this.startPrice = startPrice;
        this.endPrice = endPrice;
        this.type = type;
    }

    public Type getType() {
        return type;
    }

    public long getStartTime() {
        return startTime;
    }

    public double getEndPrice() {
        return endPrice;
    }

    public void setEndPrice(double endPrice) {
        this.endPrice = endPrice;
    }

    public long getEndTime() {
        return endTime;
    }

    public void setEndTime(long endTime) {
        this.endTime = endTime;
    }

    public void setComputedDuration(double computedDuration) {
        this.computedDuration = computedDuration;
    }

    public double size() {
        return switch (type) {
            case UP -> endPrice - startPrice;
            case DOWN -> startPrice - endPrice;
        };
    }

    public long duration() {
        return endTime - startTime;
    }

    public double speed() {
        return size() / (double) duration();
    }

    @Override
    public String toString() {
        return String.format("""
                        Imbalance
                           startTime :: %s (%d)
                           startPrice :: %.2f
                           endTime :: %s (%d)
                           endPrice :: %.2f
                           duration :: %d
                           size :: %.2f
                           speed :: %.2f
                           computedDuration :: %.2f
                        """,
                TimeFormatter.format(startTime),
                startTime,
                startPrice,
                TimeFormatter.format(endTime),
                endTime,
                endPrice,
                duration(),
                size(),
                speed(),
                computedDuration);
    }
}
