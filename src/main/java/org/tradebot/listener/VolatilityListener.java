package org.tradebot.listener;

public interface VolatilityListener {
    void notify(double volatility, double average);
}
