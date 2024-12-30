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
    private final UserDataHandler userDataHandler;
    private final RestAPIService apiService;
    private boolean userDataStream = false;
    private String listenKey = "";

    private ScheduledExecutorService pingUserDataStreamScheduler, pingWSConnectionTaskScheduler;

    public WebSocketService(TradeHandler tradeHandler,
                            OrderBookHandler orderBookHandler,
                            UserDataHandler userDataHandler,
                            RestAPIService apiService) {
        super(URI.create("wss://fstream.binance.com/ws"));
        this.tradeHandler = tradeHandler;
        this.orderBookHandler = orderBookHandler;
        this.userDataHandler = userDataHandler;
        this.apiService = apiService;

        if (pingWSConnectionTaskScheduler == null || pingWSConnectionTaskScheduler.isShutdown()) {
            pingWSConnectionTaskScheduler = Executors.newScheduledThreadPool(1);
        }
        pingWSConnectionTaskScheduler.scheduleAtFixedRate(() -> {
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
            String eventType = message.getString("e");
            if ("trade".equals(eventType)) {
                tradeHandler.onMessage(message);
            } else if ("depthUpdate".equals(eventType)) {
                orderBookHandler.onMessage(message);
            } else {
                userDataHandler.onMessage(eventType, message);
            }
        } else if (message.has("id")) {
            int id = message.getInt("id");
            if (id == 1 && "null".equals(message.get("result").toString())) {
                tradeHandler.start();
            } else if (id == 2 && "null".equals(message.get("result").toString())) {
                orderBookHandler.start();
            } else if (id == 3 && "null".equals(message.get("result").toString())) {
                userDataStream = true;
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
        closeUserDataStream();

        if (pingWSConnectionTaskScheduler != null && !pingWSConnectionTaskScheduler.isShutdown()) {
            pingWSConnectionTaskScheduler.shutdownNow();
            pingWSConnectionTaskScheduler = null;
        }
    }

    public void closeUserDataStream() {
        if (userDataStream) {
            send(String.format("{\"method\": \"UNSUBSCRIBE\", \"params\": [\"%s\"], \"id\": 3}", listenKey));
            if (pingUserDataStreamScheduler != null && !pingUserDataStreamScheduler.isShutdown()) {
                pingUserDataStreamScheduler.shutdownNow();
                pingUserDataStreamScheduler = null;
            }
            apiService.removeUserStreamKey();
            userDataStream = false;
        }
    }

    public void openUserDataStream() {
        if (!userDataStream) {
            listenKey = apiService.getUserStreamKey();
            send(String.format("{\"method\": \"SUBSCRIBE\", \"params\": [\"%s\"], \"id\": 3}", listenKey));

            if (pingUserDataStreamScheduler == null || pingUserDataStreamScheduler.isShutdown()) {
                pingUserDataStreamScheduler = Executors.newScheduledThreadPool(1);
            }
            pingUserDataStreamScheduler.scheduleAtFixedRate(() -> {
                if (userDataStream) {
                    apiService.keepAliveUserStreamKey();
                }
            }, 59, 59, TimeUnit.MINUTES);
        }
    }
}