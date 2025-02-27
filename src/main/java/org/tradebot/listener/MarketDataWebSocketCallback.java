package org.tradebot.listener;

public interface MarketDataWebSocketCallback {

    void notifyMarketDataWSStateChanged(boolean ready);
}
