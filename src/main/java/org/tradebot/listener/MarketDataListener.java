package org.tradebot.listener;

import org.tradebot.domain.MarketEntry;

public interface MarketDataListener {

    void notify(long timestamp, MarketEntry marketEntry) throws Exception;
}
