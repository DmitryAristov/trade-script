package org.tradebot.listener;

import org.jetbrains.annotations.Nullable;
import org.tradebot.domain.Position;

public interface UserDataCallback {

    void notifyOrderUpdate(String clientId, String status);
    void notifyPositionUpdate(@Nullable Position position);
}
