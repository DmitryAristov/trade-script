package org.tradebot.listener;

import org.tradebot.domain.Position;

public interface UserDataCallback {

    void notifyOrderUpdate(String clientId, String status);
    void notifyPositionUpdate(Position position);
}
