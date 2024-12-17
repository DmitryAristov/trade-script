package org.tradebot.domain;

import java.io.Serializable;


/**
 * Для хранения и анализа ежесекундных данных.
 */
public record MarketEntry(double high, double low, double volume) implements Serializable {

    public double average() {
        return (low + high) / 2.;
    }

    public double size() {
        return high - low;
    }
}
