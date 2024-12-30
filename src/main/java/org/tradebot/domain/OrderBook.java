package org.tradebot.domain;

import java.util.Map;

public record OrderBook(long lastUpdateId, Map<Double, Double> asks, Map<Double, Double> bids) {  }

