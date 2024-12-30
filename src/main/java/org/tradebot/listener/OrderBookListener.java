package org.tradebot.listener;

import java.util.Map;

public interface OrderBookListener {

    void notifyOrderBookUpdate(Map<Double, Double> asks, Map<Double, Double> bids);

}
