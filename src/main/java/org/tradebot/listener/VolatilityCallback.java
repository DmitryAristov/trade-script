package org.tradebot.listener;

public interface VolatilityCallback {

    void notifyVolatilityUpdate(double volatility, double average);
}
