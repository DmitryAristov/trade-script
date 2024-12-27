package org.tradebot.binance;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;
import org.tradebot.util.Log;

import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.tradebot.TradingBot.SYMBOL;

public class WebSocketService extends WebSocketClient {

    private final TradeHandler tradeHandler;
    private final OrderBookHandler orderBookHandler;

    public WebSocketService(TradeHandler tradeHandler, OrderBookHandler orderBookHandler) {
        super(URI.create("wss://fstream.binance.com/ws"));
        this.tradeHandler = tradeHandler;
        this.orderBookHandler = orderBookHandler;

        ScheduledExecutorService pingTaskScheduler = Executors.newScheduledThreadPool(1);
        pingTaskScheduler.scheduleAtFixedRate(() -> {
            if (this.isOpen()) {
                this.sendPing();
            }
        }, 5, 5, TimeUnit.MINUTES);
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        send(String.format("{\"method\": \"SUBSCRIBE\", \"params\": [\"%s@trade\"], \"id\": 1}", SYMBOL.toLowerCase()));
        send(String.format("{\"method\": \"SUBSCRIBE\", \"params\": [\"%s@depth@100ms\"], \"id\": 2}", SYMBOL.toLowerCase()));
    }

    @Override
    public void onMessage(String msg) {
        JSONObject message = new JSONObject(msg);
        if (message.has("e")) {
            String e = message.getString("e");
            if (e.equals("trade")) {
                tradeHandler.onMessage(message);
            } else if (e.equals("depthUpdate")) {
                orderBookHandler.onMessage(message);
            }
        } else if (message.has("id")) {
            int id = message.getInt("id");
            if (id == 1 && message.get("result").toString().equals("null")) {
                tradeHandler.start();
            } else if (id == 2 && message.get("result").toString().equals("null")) {
                orderBookHandler.start();
            }
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        Log.info("WebSocket closed");
    }

    @Override
    public void onError(Exception e) {
        Log.debug(e);
    }

    public void unsubscribe() {
        send(String.format("{\"method\": \"UNSUBSCRIBE\", \"params\": [\"%s@trade\"], \"id\": 1}", SYMBOL.toLowerCase()));
        tradeHandler.stop();
        send(String.format("{\"method\": \"UNSUBSCRIBE\", \"params\": [\"%s@depth@100ms\"], \"id\": 2}", SYMBOL.toLowerCase()));
        orderBookHandler.stop();
    }
}