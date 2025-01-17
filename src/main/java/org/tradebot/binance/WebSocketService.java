package org.tradebot.binance;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;
import org.tradebot.listener.ReadyStateCallback;
import org.tradebot.listener.WebSocketCallback;
import org.tradebot.service.TaskManager;
import org.tradebot.util.Log;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
        super(URI.create("wss://stream.binancefuture.com/ws"));//"wss://fstream.binance.com/ws"
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
        send(String.format("{\"method\": \"SUBSCRIBE\", \"params\": [\"%s@trade\"], \"id\": 1}", symbol.toLowerCase()));
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
                case "trade" -> tradeHandler.onMessage(message);
                case "depthUpdate" -> orderBookHandler.onMessage(message);
                default -> userDataHandler.onMessage(message.getString("e"), message);
            }
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        log.warn(String.format("WebSocket closed (code: %d, reason: %s, remote: %s)", code, reason, remote));
        if (code != 1000) {
            log.info("Scheduling WebSocket reconnect...");
            taskManager.scheduleAtFixedRate("websocket_reconnect", this::reconnectWebSocket,
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
        try {
            reconnectBlocking();
            log.info("WebSocket reconnected successfully.");
        } catch (InterruptedException e) {
            log.error("Failed to reconnect WebSocket", e);
            Thread.currentThread().interrupt();
        }
    }

    protected void unsubscribeFromStreams() {
        try {
            streamsConnected.set(false);
            updateReadyState();

            log.info("Unsubscribing from WebSocket trade stream...");
            send(String.format("{\"method\": \"UNSUBSCRIBE\", \"params\": [\"%s@trade\"], \"id\": 1}", symbol.toLowerCase()));
            log.info("Unsubscribing from WebSocket depth stream...");
            send(String.format("{\"method\": \"UNSUBSCRIBE\", \"params\": [\"%s@depth@100ms\"], \"id\": 2}", symbol.toLowerCase()));
            disconnectUserDataStream();
            cancelTasks();
            log.info("User data stream closed.");
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
                24 * 60 - 5, 24 * 60 - 5, TimeUnit.MINUTES);
//        taskManager.scheduleAtFixedRate("fake_disconnect", this::fakeDisconnect, 25, 25, TimeUnit.MILLISECONDS);
        tradeHandler.scheduleTasks();
        orderBookHandler.scheduleTasks();
        log.info("Periodic tasks scheduled");
    }

    protected void cancelTasks() {
        log.info("Cancelling periodic tasks...");
        taskManager.cancel(WEBSOCKET_PING_TASK_KEY);
        taskManager.cancel(WEBSOCKET_RECONNECT_TASK_KEY);
        tradeHandler.cancelTasks();
        orderBookHandler.cancelTasks();
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
                apiService.keepAliveUserStreamKey();
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
            if (callback != null)
                callback.notifyWebsocketStateChanged(ready);
        }
    }

    public void setCallback(WebSocketCallback callback) {
        this.callback = callback;
        log.info(String.format("Callback set: %s", callback.getClass().getName()));
    }

    public void logAll() {
        log.debug(String.format("""
                        WebSocket State:
                        Symbol: %s
                        ListenKey: %s
                        IsOpen: %s
                        IsReady: %s
                        orderBookReady: %s
                        """,
                symbol, listenKey, this.isOpen(), streamsConnected.get(), orderBookReady.get()));
    }

//    public static int reconnectsCount = 0;
//    private void fakeDisconnect() {
//        long openTime = System.currentTimeMillis() - fakeOpenTime;
//        if (openTime > -15L + reconnectsCount * 10L && openTime < reconnectsCount * 10L) {
//            if (!streamsConnected.get())
//                return;
//            log.info("Simulating lost WebSocket connection...");
//            streamsConnected.set(false);
//            updateReadyState();
//
//            long rerunTaskDelay = new Random().nextLong(1_000L, 180_000L);
//            log.info("Reconnect on " + TimeFormatter.format(System.currentTimeMillis() + rerunTaskDelay));
//            taskManager.schedule("reset_ws_ready_state", () -> {
//
//                streamsConnected.set(true);
//                updateReadyState();
//
//                log.info("Connection recovered back " + reconnectsCount++);
//                if (System.currentTimeMillis() > fakeOpenTime) {
//                    fakeOpenTime = System.currentTimeMillis() + 30_000L;
//                    log.info("Updating next open position time to " + TimeFormatter.format(fakeOpenTime));
//                }
//            }, rerunTaskDelay, TimeUnit.MILLISECONDS);
//        }
//    }
}