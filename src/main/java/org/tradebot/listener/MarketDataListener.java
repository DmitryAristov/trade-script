package org.tradebot.listener;

import org.tradebot.domain.MarketEntry;

public interface MarketDataListener {

    void notifyNewMarketEntry(long timestamp, MarketEntry marketEntry);
}
