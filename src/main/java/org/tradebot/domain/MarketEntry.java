package org.tradebot.domain;

public record MarketEntry(double high, double low, double volume) {

    public double average() {
        return (low + high) / 2.;
    }

    public double size() {
        return high - low;
    }
}
