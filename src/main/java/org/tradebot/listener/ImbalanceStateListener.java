package org.tradebot.listener;

import org.tradebot.enums.ImbalanceState;

public interface ImbalanceStateListener {

    void notify(long time, ImbalanceState state);
}
