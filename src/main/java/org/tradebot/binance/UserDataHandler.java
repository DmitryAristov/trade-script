package org.tradebot.binance;

import org.json.JSONArray;
import org.json.JSONObject;
import org.tradebot.domain.Position;
import org.tradebot.listener.UserDataCallback;
import org.tradebot.service.TaskManager;
import org.tradebot.util.Log;

import java.util.concurrent.TimeUnit;

import static org.tradebot.util.JsonParser.parseBalance;
import static org.tradebot.util.JsonParser.parsePosition;
import static org.tradebot.util.Settings.BALANCE_UPDATE_TASK;
import static org.tradebot.util.Settings.SYMBOL;

public class UserDataHandler {
    private final Log log;
    private UserDataCallback callback;
    private final String baseAsset;
    private final int clientNumber;

    public UserDataHandler(int clientNumber, String baseAsset) {
        this.log = new Log(clientNumber);
        this.clientNumber = clientNumber;
        this.baseAsset = baseAsset;
    }

    public void onMessage(String eventType, JSONObject message) {
        log.debug(String.format("Received event: '%s'. Message: '%s'", eventType, message));
        if ("ORDER_TRADE_UPDATE".equals(eventType)) {
            JSONObject orderJson = message.getJSONObject("o");
            String status = orderJson.getString("X");
            String clientId = orderJson.getString("c");
            if (!validOrderUpdate(orderJson))
                return;

            if (callback != null)
                callback.notifyOrderUpdate(clientId, status);

        } else if ("ACCOUNT_UPDATE".equals(eventType)) {
            JSONObject accountUpdate = message.getJSONObject("a");
            if ("ORDER".equals(accountUpdate.getString("m"))) {
                JSONArray positionUpdates = accountUpdate.getJSONArray("P");
                for (int i = 0; i < positionUpdates.length(); i++) {
                    JSONObject positionUpdate = positionUpdates.getJSONObject(i);
                    if (!validPositionUpdate(positionUpdate))
                        continue;

                    if (callback != null) {
                        Position position = parsePosition(positionUpdate);
                        callback.notifyPositionUpdate(position);

                        TaskManager.getInstance(clientNumber).schedule(BALANCE_UPDATE_TASK,
                                () -> updateBalance(accountUpdate, position), 100, TimeUnit.MILLISECONDS);
                    }
                    break;
                }
            }
        }
    }

    private boolean validOrderUpdate(JSONObject orderJson) {
        return SYMBOL.toUpperCase().equals(orderJson.getString("s"));
    }

    private boolean validPositionUpdate(JSONObject positionUpdate) {
        return positionUpdate.has("ps") &&
                "BOTH".equals(positionUpdate.getString("ps")) &&
                positionUpdate.has("s") &&
                SYMBOL.toUpperCase().equals(positionUpdate.getString("s"));
    }

    private void updateBalance(JSONObject accountUpdate, Position position) {
        double walletBalance = parseBalance(accountUpdate.getJSONArray("B"), baseAsset);
        log.writeAccountUpdateEvent(walletBalance, position);
    }

    public void setCallback(UserDataCallback callback) {
        this.callback = callback;
        log.info(String.format("Callback set: %s", callback.getClass().getName()));
    }
}
