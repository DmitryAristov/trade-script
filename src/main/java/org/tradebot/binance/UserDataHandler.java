package org.tradebot.binance;

import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONObject;
import org.tradebot.domain.Position;
import org.tradebot.listener.UserDataCallback;
import org.tradebot.util.Log;

public class UserDataHandler {
    private final Log log = new Log();
    private final String symbol;
    protected UserDataCallback callback;

    public UserDataHandler(String symbol) {
        this.symbol = symbol;
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

                    if (callback != null)
                        callback.notifyPositionUpdate(parsePosition(positionUpdate));
                    break;
                }
            }
        }
    }

    private boolean validOrderUpdate(JSONObject orderJson) {
        return this.symbol.toUpperCase().equals(orderJson.getString("s"));
    }

    private boolean validPositionUpdate(JSONObject positionUpdate) {
        return positionUpdate.has("ps") &&
                "BOTH".equals(positionUpdate.getString("ps")) &&
                positionUpdate.has("s") &&
                symbol.toUpperCase().equals(positionUpdate.getString("s"));
    }

    private static @Nullable Position parsePosition(JSONObject positionUpdate) {
        Position position = new Position();
        double entryPrice = Double.parseDouble(positionUpdate.getString("ep"));
        if (entryPrice == 0)
            return null;
        double positionAmount = Double.parseDouble(positionUpdate.getString("pa"));
        if (positionAmount == 0)
            return null;

        position.setSymbol(positionUpdate.getString("s"));
        position.setEntryPrice(entryPrice);
        position.setPositionAmt(positionAmount);
        position.setBreakEvenPrice(Double.parseDouble(positionUpdate.getString("bep")));
        return position;
    }

    public void setCallback(UserDataCallback callback) {
        this.callback = callback;
        log.info(String.format("Callback set: %s", callback.getClass().getName()));
    }

    public void logAll() {
        log.debug(String.format("callback: %s", callback));
    }
}
