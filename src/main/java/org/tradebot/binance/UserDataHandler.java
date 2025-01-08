package org.tradebot.binance;

import org.json.JSONArray;
import org.json.JSONObject;
import org.tradebot.domain.Position;
import org.tradebot.listener.UserDataCallback;
import org.tradebot.service.TradingBot;
import org.tradebot.util.Log;

public class UserDataHandler {
    protected UserDataCallback callback;

    public void onMessage(String eventType, JSONObject message) {
        Log.debug("user data stream message :: " + message.toString());
        if ("ORDER_TRADE_UPDATE".equals(eventType)) {
            JSONObject orderJson = message.getJSONObject("o");
            String status = orderJson.getString("X");
            String clientId = orderJson.getString("c");

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

                    Position position = new Position();
                    position.setPositionAmt(Double.parseDouble(positionUpdate.getString("pa")));
                    position.setEntryPrice(Double.parseDouble(positionUpdate.getString("ep")));
                    if (position.getEntryPrice() == 0 || position.getPositionAmt() == 0)
                        position = null;

                    if (callback != null)
                        callback.notifyPositionUpdate(position);
                    break;
                }
            }
        }
    }

    private boolean validPositionUpdate(JSONObject positionUpdate) {
        return positionUpdate.has("ps") &&
                "BOTH".equals(positionUpdate.getString("ps")) &&
                positionUpdate.has("s") &&
                TradingBot.getInstance().symbol.toUpperCase().equals(positionUpdate.getString("s"));
    }

    public void setCallback(UserDataCallback callback) {
        this.callback = callback;
        Log.info(String.format("callback added %s", callback.getClass().getName()));
    }

    public void logAll() {
        Log.debug(String.format("callback :: %s", callback));
    }
}
