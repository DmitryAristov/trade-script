package org.tradebot.listener;

import org.tradebot.domain.Imbalance;
import org.tradebot.service.ImbalanceService;

public interface ImbalanceStateListener {

    void notifyImbalanceStateUpdate(long time, ImbalanceService.State state, Imbalance currentImbalance);
}
