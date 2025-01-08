package org.tradebot.domain;

public record MarketEntry(double high, double low, double volume) {

    public double average() {
        return (low + high) / 2.;
    }

    public double size() {
        return high - low;
    }

    @Override
    public String toString() {
        return String.format("{ high :: %.2f, low :: %.2f, volume :: %.2f }", high, low, volume);
    }
}
