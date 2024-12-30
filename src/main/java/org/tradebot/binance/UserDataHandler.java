package org.tradebot.binance;

import org.json.JSONObject;
import org.tradebot.listener.UserDataListener;
import org.tradebot.util.Log;

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

        }
    }

    public void subscribe(UserDataListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
            Log.info(String.format("listener added %s", listener.getClass().getName()));
        }
    }

    public void unsubscribe(UserDataListener listener) {
        listeners.remove(listener);
        Log.info(String.format("listener removed %s", listener.getClass().getName()));
    }
}
