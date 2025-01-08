package org.tradebot.listener;

import java.util.Map;

public interface OrderBookCallback {

    void notifyOrderBookUpdate(Map<Double, Double> asks, Map<Double, Double> bids);

}
