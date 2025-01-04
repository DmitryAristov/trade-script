package org.tradebot.binance;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;
import org.tradebot.listener.WebSocketListener;
import org.tradebot.util.Log;
import org.tradebot.util.TaskManager;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class WebSocketService extends WebSocketClient {

    private final TradeHandler tradeHandler;
    private final OrderBookHandler orderBookHandler;
    private final UserDataHandler userDataHandler;
    private final RestAPIService apiService;
    private final TaskManager taskManager;
    private final String symbol;
    private final List<WebSocketListener> listeners = new ArrayList<>();

    protected final AtomicBoolean userDataStream = new AtomicBoolean(false);
    private String listenKey = "";
    private final AtomicBoolean isReady = new AtomicBoolean(false);

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

        setupTasks();
        Log.info("service created");
    }

    private void setupTasks() {
        taskManager.create("websocket_ping", () -> {
            if (isReady()) {
                Log.info("websocket ping");
                this.sendPing();
            }
        }, TaskManager.Type.PERIOD, 5, 5, TimeUnit.MINUTES);

        taskManager.create("websocket_reconnect", this::reconnectWebSocket, TaskManager.Type.PERIOD,
                24 * 60 - 5, 24 * 60 - 5, TimeUnit.MINUTES);
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        send(String.format("{\"method\": \"SUBSCRIBE\", \"params\": [\"%s@trade\"], \"id\": 1}", symbol.toLowerCase()));
        send(String.format("{\"method\": \"SUBSCRIBE\", \"params\": [\"%s@depth@100ms\"], \"id\": 2}", symbol.toLowerCase()));
        setReady(true);
        updateUserDataStream(userDataStream.get());
        Log.info("service started");
    }

    @Override
    public void onMessage(String msg) {
        JSONObject message = new JSONObject(msg);
        if (message.has("e")) {
            switch (message.getString("e")) {
                case "trade" -> tradeHandler.onMessage(message);
                case "depthUpdate" -> orderBookHandler.onMessage(message);
                default -> userDataHandler.onMessage(message.getString("e"), message);
            }
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        setReady(false);
        Log.info(String.format("WebSocket closed: %d, %s", code, reason));
    }

    @Override
    public void onError(Exception e) {
        setReady(false);
        Log.error(e);
        reconnectWebSocket();
    }

    protected void reconnectWebSocket() {
        Log.info("reconnecting WebSocket...");
        boolean wasUserDataStreamActive = userDataStream.get();
        unsubscribeFromStreams();
        reconnect();

        if (wasUserDataStreamActive) {
            updateUserDataStream(true);
        }
    }


    public void stop() {
        Log.info("stopping WebSocket service...");
        setReady(false);
        unsubscribeFromStreams();
        close();
    }

    private void unsubscribeFromStreams() {
        send(String.format("{\"method\": \"UNSUBSCRIBE\", \"params\": [\"%s@trade\"], \"id\": 1}", symbol.toLowerCase()));
        send(String.format("{\"method\": \"UNSUBSCRIBE\", \"params\": [\"%s@depth@100ms\"], \"id\": 2}", symbol.toLowerCase()));
        if (userDataStream.get()) {
            send(String.format("{\"method\": \"UNSUBSCRIBE\", \"params\": [\"%s\"], \"id\": 3}", listenKey));
            taskManager.stop("user_data_stream_ping");
            apiService.removeUserStreamKey();
            userDataStream.set(false);
        }
    }

    public void updateUserDataStream(boolean enable) {
        if (enable) {
            if (isReady()) {
                listenKey = apiService.getUserStreamKey();
                send(String.format("{\"method\": \"SUBSCRIBE\", \"params\": [\"%s\"], \"id\": 3}", listenKey));

                taskManager.create("user_data_stream_ping", () -> {
                    if (userDataStream.get()) {
                        Log.info("User data stream keep alive");
                        apiService.keepAliveUserStreamKey();
                    }
                }, TaskManager.Type.PERIOD, 59, 59, TimeUnit.MINUTES);
            }
            userDataStream.set(true);
            Log.info("User data stream started");
        } else {
            if (isReady()) {
                if (userDataStream.get()) {
                    send(String.format("{\"method\": \"UNSUBSCRIBE\", \"params\": [\"%s\"], \"id\": 3}", listenKey));
                    taskManager.stop("user_data_stream_ping");
                    apiService.removeUserStreamKey();
                }
            }
            userDataStream.set(false);
            Log.info("User data stream stopped");
        }
    }

    public boolean isReady() {
        return isReady.get() && isOpen();
    }

    private void setReady(boolean ready) {
        isReady.set(ready);
        listeners.forEach(listener -> listener.notifyWebsocketStateChanged(ready));
    }

    public void subscribe(WebSocketListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
            Log.info(String.format("listener added %s", listener.getClass().getName()));
        }
    }

    public void unsubscribe(WebSocketListener listener) {
        listeners.remove(listener);
        Log.info(String.format("listener removed %s", listener.getClass().getName()));
    }

    public void logAll() {
        Log.debug(String.format("symbol: %s", symbol));
        Log.debug(String.format("userDataStream: %s", userDataStream));
        Log.debug(String.format("listenKey: %s", listenKey));
        Log.debug(String.format("this.isOpen(): %s", this.isOpen()));
    }
}