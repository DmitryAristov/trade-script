package org.tradebot.binance;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;
import org.tradebot.util.Log;
import org.tradebot.util.TaskManager;

import java.net.URI;
import java.util.concurrent.TimeUnit;

public class WebSocketService extends WebSocketClient {

    private final TradeHandler tradeHandler;
    private final OrderBookHandler orderBookHandler;
    private final UserDataHandler userDataHandler;
    private final RestAPIService apiService;
    private final TaskManager taskManager;
    private final String symbol;
    private boolean userDataStream = false;
    private String listenKey = "";

    public WebSocketService(String symbol,
                            TradeHandler tradeHandler,
                            OrderBookHandler orderBookHandler,
                            UserDataHandler userDataHandler,
                            RestAPIService apiService,
                            TaskManager taskManager) {
        super(URI.create("wss://fstream.binance.com/ws"));
        this.symbol = symbol;
        this.tradeHandler = tradeHandler;
        this.orderBookHandler = orderBookHandler;
        this.userDataHandler = userDataHandler;
        this.apiService = apiService;
        this.taskManager = taskManager;

        this.taskManager.create("websocket_ping", () -> {
            if (this.isOpen()) {
                Log.info("websocket ping");
                this.sendPing();
            }
        }, TaskManager.Type.PERIOD, 5, 5, TimeUnit.MINUTES);
        //TODO test reconnection from here
        this.taskManager.create("websocket_reconnect", () -> {
            if (this.isOpen()) {
                Log.info("websocket reconnect");
                this.reconnect();
            }
        }, TaskManager.Type.PERIOD, 24 * 60 -  5, 24 * 60 -  5, TimeUnit.MINUTES);

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

    public void stop() {
        send(String.format("{\"method\": \"UNSUBSCRIBE\", \"params\": [\"%s@trade\"], \"id\": 1}", symbol.toLowerCase()));
        send(String.format("{\"method\": \"UNSUBSCRIBE\", \"params\": [\"%s@depth@100ms\"], \"id\": 2}", symbol.toLowerCase()));
        closeUserDataStream();
        
        Log.info("service stopped");
    }

    public void closeUserDataStream() {
        if (userDataStream) {
            userDataStream = false;
            send(String.format("{\"method\": \"UNSUBSCRIBE\", \"params\": [\"%s\"], \"id\": 3}", listenKey));
            taskManager.stop("user_data_stream_ping");
            apiService.removeUserStreamKey();
        }
    }

    public void openUserDataStream() {
        if (!userDataStream) {
            userDataStream = true;
            listenKey = apiService.getUserStreamKey();
            send(String.format("{\"method\": \"SUBSCRIBE\", \"params\": [\"%s\"], \"id\": 3}", listenKey));

            taskManager.create("user_data_stream_ping", () -> {
                if (userDataStream) {
                    Log.info("user data stream keep alive");
                    apiService.keepAliveUserStreamKey();
                }
            }, TaskManager.Type.PERIOD, 59, 59, TimeUnit.MINUTES);
            Log.info("user data stream started");
        }
    }

    public void logAll() {
        Log.debug(String.format("symbol: %s", symbol));
        Log.debug(String.format("userDataStream: %s", userDataStream));
        Log.debug(String.format("listenKey: %s", listenKey));
        Log.debug(String.format("this.isOpen(): %s", this.isOpen()));
    }
}