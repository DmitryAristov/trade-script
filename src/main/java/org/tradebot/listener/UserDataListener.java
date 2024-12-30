package org.tradebot.listener;

import org.json.JSONObject;

public interface UserDataListener {

    void notifyOrderUpdate(String clientId, String status);
    void notifyAccountAndPositionUpdate(JSONObject updateData);
}
