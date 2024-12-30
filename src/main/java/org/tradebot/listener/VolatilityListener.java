package org.tradebot.listener;

public interface VolatilityListener {

    void notifyVolatilityUpdate(double volatility, double average);
}
