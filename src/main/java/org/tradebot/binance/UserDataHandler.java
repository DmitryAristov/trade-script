package org.tradebot.binance;

import org.json.JSONObject;
import org.tradebot.listener.UserDataListener;

import java.util.ArrayList;
import java.util.List;

public class UserDataHandler {
    private final List<UserDataListener> listeners = new ArrayList<>();

    public void onMessage(String eventType, JSONObject message) {
        if ("ORDER_TRADE_UPDATE".equals(eventType)) {
            JSONObject orderJson = message.getJSONObject("o");
            String status = orderJson.getString("X");
            String clientId = orderJson.getString("c");

            listeners.forEach(listener -> listener.notifyOrderUpdate(clientId, status));

        } else if ("ACCOUNT_UPDATE".equals(eventType)) {
            JSONObject updateData = message.getJSONObject("a");
            if ("ORDER".equals(updateData.getString("m"))) {
                listeners.forEach(listener -> listener.notifyAccountAndPositionUpdate(updateData));
            }

        }
    }

    public void subscribe(UserDataListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void unsubscribe(UserDataListener listener) {
        listeners.remove(listener);
    }
}
