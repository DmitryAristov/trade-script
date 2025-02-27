package org.tradebot.listener;

public interface OrderBookStateCallback {

    void notifyOrderBookStateUpdate(boolean ready);

}
