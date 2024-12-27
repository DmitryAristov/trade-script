package org.tradebot.listener;

import java.util.Map;

public interface OrderBookListener {

    void notify(Map<Double, Double> asks, Map<Double, Double> bids);

}
