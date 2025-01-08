package org.tradebot.listener;

import org.tradebot.domain.MarketEntry;

public interface MarketDataCallback {

    void notifyNewMarketEntry(long timestamp, MarketEntry marketEntry);
}
