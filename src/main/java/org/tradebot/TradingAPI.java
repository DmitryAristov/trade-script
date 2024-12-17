package org.tradebot;

import org.tradebot.domain.MarketEntry;
import org.tradebot.domain.Order;
import org.tradebot.domain.Position;
import org.tradebot.listener.MarketDataListener;

import java.util.List;
import java.util.TreeMap;

public interface TradingAPI {

    void createLimitOrder(Order order) throws Exception;

    void setLeverage(int leverage) throws Exception;

    double getAccountBalance() throws Exception;

    List<Position> getOpenPositions() throws Exception;

    TreeMap<Long, MarketEntry> getMarketData(long interval, long lookbackPeriod) throws Exception;

    void subscribe(MarketDataListener listener);

    void unsubscribe(MarketDataListener listener);
}
