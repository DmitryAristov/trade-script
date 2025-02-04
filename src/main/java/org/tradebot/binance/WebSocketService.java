package org.tradebot.binance;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;
import org.tradebot.domain.APIError;
import org.tradebot.domain.HTTPResponse;
import org.tradebot.listener.ReadyStateCallback;
import org.tradebot.listener.WebSocketCallback;
import org.tradebot.service.TaskManager;
import org.tradebot.util.Log;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.tradebot.service.TradingBot.WS_URL;

public class WebSocketService extends WebSocketClient implements ReadyStateCallback {

    public static final String USER_DATA_STREAM_PING_TASK_KEY = "user_data_stream_ping";
    public static final String WEBSOCKET_PING_TASK_KEY = "websocket_ping";
    public static final String WEBSOCKET_RECONNECT_TASK_KEY = "websocket_reconnect";

    private final Log log = new Log();

    private final TradeHandler tradeHandler;
    private final OrderBookHandler orderBookHandler;
    private final UserDataHandler userDataHandler;
    private final APIService apiService;
    private final TaskManager taskManager;
    private final String symbol;
    private WebSocketCallback callback;

    private String listenKey = null;
    protected final AtomicBoolean streamsConnected = new AtomicBoolean(false);
    protected final AtomicBoolean orderBookReady = new AtomicBoolean(false);
    protected final AtomicBoolean webSocketReady = new AtomicBoolean(false);

    public WebSocketService(String symbol,
                            TradeHandler tradeHandler,
                            OrderBookHandler orderBookHandler,
                            UserDataHandler userDataHandler,
                            APIService apiService,
                            TaskManager taskManager) {
        super(URI.create(WS_URL));
        this.symbol = symbol;
        this.tradeHandler = tradeHandler;
        this.orderBookHandler = orderBookHandler;
        this.userDataHandler = userDataHandler;
        this.apiService = apiService;
        this.taskManager = taskManager;
        log.info("WebSocketService initialized");
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        log.info("WebSocket connection opened.");
        scheduleTasks();
        log.info("Subscribing WebSocket trade stream...");
        send(String.format("{\"method\": \"SUBSCRIBE\", \"params\": [\"%s@aggTrade\"], \"id\": 1}", symbol.toLowerCase()));
        log.info("Subscribing WebSocket depth stream...");
        send(String.format("{\"method\": \"SUBSCRIBE\", \"params\": [\"%s@depth@100ms\"], \"id\": 2}", symbol.toLowerCase()));
        connectUserDataStream();
        streamsConnected.set(true);
        updateReadyState();

        log.info("WebSocket service started.");
    }

    @Override
    public void onMessage(String msg) {
        JSONObject message = new JSONObject(msg);

        if (message.has("e")) {
            String eventType = message.getString("e");
            switch (eventType) {
                case "aggTrade" -> tradeHandler.onMessage(message);
                case "depthUpdate" -> orderBookHandler.onMessage(message);
                default -> userDataHandler.onMessage(message.getString("e"), message);
            }
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        log.warn(String.format("WebSocket closed (code: %d, reason: %s, remote: %s)", code, reason, remote));
        if (code != 1000) {
            log.info("Got close code != 1000 - scheduling WebSocket reconnect...");
            taskManager.scheduleAtFixedRate(WEBSOCKET_RECONNECT_TASK_KEY, this::reconnectWebSocket,
                    1, 24 * 60 - 5, TimeUnit.MINUTES);
        }
    }

    @Override
    public void onError(Exception e) {
        log.error("WebSocket encountered an error", e);
    }

    protected void reconnectWebSocket() {
        log.info("Reconnecting WebSocket...");
        unsubscribeFromStreams();
        cancelTasks();
        reconnect();
        log.info("WebSocket reconnected successfully.");
    }

    protected void unsubscribeFromStreams() {
        try {
            streamsConnected.set(false);
            updateReadyState();

            log.info("Unsubscribing from WebSocket trade stream...");
            send(String.format("{\"method\": \"UNSUBSCRIBE\", \"params\": [\"%s@aggTrade\"], \"id\": 1}", symbol.toLowerCase()));
            log.info("Unsubscribing from WebSocket depth stream...");
            send(String.format("{\"method\": \"UNSUBSCRIBE\", \"params\": [\"%s@depth@100ms\"], \"id\": 2}", symbol.toLowerCase()));
            disconnectUserDataStream();
        } catch (Exception e) {
            log.error("Failed to unsubscribe from WebSocket streams", e);
        }
    }

    protected void scheduleTasks() {
        log.info("Setting up periodic tasks...");

        taskManager.scheduleAtFixedRate(WEBSOCKET_PING_TASK_KEY, () -> {
            log.info("Sending WebSocket ping...");
            this.sendPing();
        }, 5, 5, TimeUnit.MINUTES);

        taskManager.scheduleAtFixedRate(WEBSOCKET_RECONNECT_TASK_KEY, this::reconnectWebSocket,
                24 * 60 - 5,  24 * 60 - 5, TimeUnit.MINUTES);

        tradeHandler.scheduleTasks();
        log.info("Periodic tasks scheduled");
    }

    protected void cancelTasks() {
        log.info("Cancelling periodic tasks...");
        taskManager.cancel(WEBSOCKET_PING_TASK_KEY);
        tradeHandler.cancelTasks();
        log.info("Periodic tasks cancelled");
    }

    protected void connectUserDataStream() {
        log.info("Subscribing user data stream...");
        listenKey = apiService.getUserStreamKey().getResponse();
        log.debug(String.format("Acquired listenKey: %s", listenKey));
        if (listenKey != null) {
            send(String.format("{\"method\": \"SUBSCRIBE\", \"params\": [\"%s\"], \"id\": 3}", listenKey));
            taskManager.scheduleAtFixedRate(USER_DATA_STREAM_PING_TASK_KEY, () -> {
                log.info("Sending user data stream ping...");
                HTTPResponse<String, APIError> response = apiService.keepAliveUserStreamKey();
                if (response.isError() && response.getError().code() == -1125) {
                    log.warn("Got error 'This listenKey does not exist.', recreating...");
                    listenKey = apiService.getUserStreamKey().getResponse();
                    log.debug(String.format("Updated listenKey: %s", listenKey));
                }
            }, 59, 59, TimeUnit.MINUTES);
            log.info("User data stream opened");
        }
    }

    protected void disconnectUserDataStream() {
        log.info("Unsubscribing user data stream...");
        if (listenKey != null) {
            send(String.format("{\"method\": \"UNSUBSCRIBE\", \"params\": [\"%s\"], \"id\": 3}", listenKey));
            log.debug(String.format("Removing listenKey: %s", listenKey));
            apiService.removeUserStreamKey();
            taskManager.cancel(USER_DATA_STREAM_PING_TASK_KEY);
            listenKey = null;
            log.info("User data stream closed");
        }
    }

    @Override
    public void close() {
        if (isClosed()) {
            log.warn("Trying to close an already closed WebSocket service.");
            return;
        }
        log.info("Closing WebSocket service...");
        unsubscribeFromStreams();
        super.close();
        log.info("WebSocket service closed.");
    }

    @Override
    public void notifyReadyStateUpdate(boolean ready) {
        this.orderBookReady.set(ready);
        updateReadyState();
    }

    private void updateReadyState() {
        boolean ready = streamsConnected.get() && isOpen() && this.orderBookReady.get();
        if (webSocketReady.get() == ready) {
            log.warn(String.format("WebSocket state unchanged: %s", ready));
        } else {
            log.info(String.format("WebSocket state changed: %s", ready));
            webSocketReady.set(ready);
            if (callback != null)
                callback.notifyWebsocketStateChanged(ready);
        }
    }

    public boolean getWebSocketReady() {
        return webSocketReady.get();
    }

    public boolean getOrderBookReady() {
        return orderBookReady.get();
    }

    public boolean getStreamsConnected() {
        return streamsConnected.get();
    }

    public void setCallback(WebSocketCallback callback) {
        this.callback = callback;
        log.info(String.format("Callback set: %s", callback.getClass().getName()));
    }

    public void logAll() {
        log.debug(String.format("""
                        WebSocket State:
                        symbol: %s
                        listenKey: %s
                        IsOpen: %s
                        streamsConnected: %s
                        orderBookReady: %s
                        webSocketReady: %s
                        """,
                symbol, listenKey, this.isOpen(), streamsConnected.get(), orderBookReady.get(), webSocketReady.get()));
    }
}