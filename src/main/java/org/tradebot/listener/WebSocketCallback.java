package org.tradebot.listener;

public interface WebSocketCallback {

    void notifyWebsocketStateChanged(boolean ready);
}
