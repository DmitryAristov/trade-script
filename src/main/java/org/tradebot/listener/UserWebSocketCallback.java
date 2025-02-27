package org.tradebot.listener;

public interface UserWebSocketCallback {

    void notifyUserDataWSChanged(boolean ready);
}
