package org.tradebot.listener;

public interface WebSocketListener {

    void notifyWebsocketStateChanged(boolean ready);
}
