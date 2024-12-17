package org.tradebot.domain;


import org.tradebot.util.TimeFormatter;

import java.io.Serializable;

public class Imbalance implements Serializable {

    public enum Type { UP, DOWN }

    private final Type type;
    private long startTime;
    private double startPrice;
    private double endPrice;
    private long endTime;
    private long completeTime;
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

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public double getStartPrice() {
        return startPrice;
    }

    public void setStartPrice(double startPrice) {
        this.startPrice = startPrice;
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

    public long getCompleteTime() {
        return completeTime;
    }

    public void setCompleteTime(long completeTime) {
        this.completeTime = completeTime;
    }

    public double getComputedDuration() {
        return computedDuration;
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
        return size() / duration();
    }

    public static Imbalance of(Imbalance imb) {
        return new Imbalance(imb.startTime, imb.startPrice, imb.endTime, imb.endPrice, imb.type);
    }

    public static Imbalance of(long startTime, double startPrice, long endTime, double endPrice, Type type) {
        return new Imbalance(startTime, startPrice, endTime, endPrice, type);
    }

    @Override
    public String toString() {
        return String.format("""
                        Imbalance
                           startTime :: %s
                           startPrice :: %.2f$
                           endTime :: %s
                           endPrice :: %.2f$
                           computedDuration :: %.2f""",
                TimeFormatter.format(startTime),
                startPrice,
                TimeFormatter.format(endTime),
                endPrice,
                computedDuration);
    }
}
