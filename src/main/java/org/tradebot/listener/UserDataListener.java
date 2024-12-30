package org.tradebot.listener;

public interface UserDataListener {

    void notifyOrderUpdate(String clientId, String status);
}
