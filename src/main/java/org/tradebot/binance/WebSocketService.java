package org.tradebot.binance;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;
import org.tradebot.util.Log;

import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class WebSocketService extends WebSocketClient {

    private final TradeHandler tradeHandler;
    private final OrderBookHandler orderBookHandler;
    private final UserDataHandler userDataHandler;
    private final RestAPIService apiService;
    private final String symbol;
    private boolean userDataStream = false;
    private String listenKey = "";

    private ScheduledExecutorService pingUserDataStreamScheduler, pingWSConnectionTaskScheduler;

    public WebSocketService(String symbol,
                            TradeHandler tradeHandler,
                            OrderBookHandler orderBookHandler,
                            UserDataHandler userDataHandler,
                            RestAPIService apiService) {
        super(URI.create("wss://fstream.binance.com/ws"));
        this.symbol = symbol;
        this.tradeHandler = tradeHandler;
        this.orderBookHandler = orderBookHandler;
        this.userDataHandler = userDataHandler;
        this.apiService = apiService;
        this.tradeHandler.start();

        if (pingWSConnectionTaskScheduler == null || pingWSConnectionTaskScheduler.isShutdown()) {
            pingWSConnectionTaskScheduler = Executors.newScheduledThreadPool(1);
        }
        pingWSConnectionTaskScheduler.scheduleAtFixedRate(() -> {
            if (this.isOpen()) {
                Log.info("websocket ping");
                this.sendPing();
            }
        }, 5, 5, TimeUnit.MINUTES);
        Log.info("service created");
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        send(String.format("{\"method\": \"SUBSCRIBE\", \"params\": [\"%s@trade\"], \"id\": 1}", symbol.toLowerCase()));
        send(String.format("{\"method\": \"SUBSCRIBE\", \"params\": [\"%s@depth@100ms\"], \"id\": 2}", symbol.toLowerCase()));
        Log.info("service started");
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
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        Log.info("WebSocket closed");
    }

    @Override
    public void onError(Exception e) {
        Log.error(e);
    }

    public void unsubscribe() {
        send(String.format("{\"method\": \"UNSUBSCRIBE\", \"params\": [\"%s@trade\"], \"id\": 1}", symbol.toLowerCase()));
        tradeHandler.stop();
        send(String.format("{\"method\": \"UNSUBSCRIBE\", \"params\": [\"%s@depth@100ms\"], \"id\": 2}", symbol.toLowerCase()));
        closeUserDataStream();

        if (pingWSConnectionTaskScheduler != null && !pingWSConnectionTaskScheduler.isShutdown()) {
            pingWSConnectionTaskScheduler.shutdownNow();
            pingWSConnectionTaskScheduler = null;
            Log.info("service stopped");
        }
    }

    public void closeUserDataStream() {
        if (userDataStream) {
            userDataStream = false;
            send(String.format("{\"method\": \"UNSUBSCRIBE\", \"params\": [\"%s\"], \"id\": 3}", listenKey));
            if (pingUserDataStreamScheduler != null && !pingUserDataStreamScheduler.isShutdown()) {
                pingUserDataStreamScheduler.shutdownNow();
                pingUserDataStreamScheduler = null;
                Log.info("user data stream stopped");
            }
            apiService.removeUserStreamKey();
        }
    }

    public void openUserDataStream() {
        if (!userDataStream) {
            userDataStream = true;
            listenKey = apiService.getUserStreamKey();
            send(String.format("{\"method\": \"SUBSCRIBE\", \"params\": [\"%s\"], \"id\": 3}", listenKey));

            if (pingUserDataStreamScheduler == null || pingUserDataStreamScheduler.isShutdown()) {
                pingUserDataStreamScheduler = Executors.newScheduledThreadPool(1);
            }
            pingUserDataStreamScheduler.scheduleAtFixedRate(() -> {
                if (userDataStream) {
                    Log.info("user data stream keep alive");
                    apiService.keepAliveUserStreamKey();
                }
            }, 59, 59, TimeUnit.MINUTES);
            Log.info("user data stream started");
        }
    }

    public void logAll() {
        Log.debug(String.format("symbol: %s", symbol));
        Log.debug(String.format("userDataStream: %s", userDataStream));
        Log.debug(String.format("listenKey: %s", listenKey));
        Log.debug(String.format("pingWSConnectionTaskScheduler isShutdown: %s", pingWSConnectionTaskScheduler.isShutdown()));
        Log.debug(String.format("pingWSConnectionTaskScheduler isTerminated: %s", pingWSConnectionTaskScheduler.isTerminated()));
        Log.debug(String.format("pingUserDataStreamScheduler isShutdown: %s", pingUserDataStreamScheduler.isShutdown()));
        Log.debug(String.format("pingUserDataStreamScheduler isTerminated: %s", pingUserDataStreamScheduler.isTerminated()));
    }
}