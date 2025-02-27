package org.tradebot.listener;

import org.tradebot.domain.Imbalance;
import org.tradebot.domain.MarketEntry;
import org.tradebot.service.ImbalanceService;

public interface ImbalanceStateCallback {

    void notifyImbalanceStateUpdate(long time, MarketEntry currentEntry, ImbalanceService.State state, Imbalance imbalance);
}
