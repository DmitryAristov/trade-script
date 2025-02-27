package org.tradebot.domain;

import org.json.JSONObject;
import org.tradebot.util.TimeFormatter;

public record APIError(int code, String msg) {

    @Override
    public String toString() {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("code", code);
        jsonObject.put("msg", msg);
        jsonObject.put("timestamp", TimeFormatter.now());
        return jsonObject.toString(4);
    }
}
