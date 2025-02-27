package org.tradebot.binance;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;
import org.tradebot.listener.OrderBookStateCallback;
import org.tradebot.listener.MarketDataWebSocketCallback;
import org.tradebot.service.TaskManager;
import org.tradebot.util.Log;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.tradebot.util.Settings.*;

public class MarketDataWebSocketService extends WebSocketClient implements OrderBookStateCallback {

    private final Log log = new Log();

    private final TradeHandler tradeHandler;
    private final OrderBookHandler orderBookHandler;
    private final TaskManager taskManager;

    private final AtomicBoolean orderBookReady = new AtomicBoolean(false);
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final List<MarketDataWebSocketCallback> callbacks = new ArrayList<>();

    private static MarketDataWebSocketService instance;

    public static MarketDataWebSocketService getInstance() {
        if (instance == null) {
            instance = new MarketDataWebSocketService();
        }
        return instance;
    }

    private MarketDataWebSocketService() {
        super(URI.create(WEB_SOCKET_URL));
        this.tradeHandler = TradeHandler.getInstance();
        this.orderBookHandler = OrderBookHandler.getInstance();
        this.taskManager =  TaskManager.getInstance();
        log.info("MarketDataWebSocketService initialized");
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        log.info("MarketDataWebSocket connection opened.");

        taskManager.scheduleAtFixedRate(WEBSOCKET_PING_TASK_KEY, this::ping, 5, 5, TimeUnit.MINUTES);

        taskManager.schedule(WEBSOCKET_RECONNECT_TASK_KEY, this::reconnect, WEBSOCKET_RECONNECT_PERIOD, TimeUnit.MINUTES);
        tradeHandler.scheduleTasks();

        log.info("Subscribing trade stream...");
        send(String.format("{\"method\": \"SUBSCRIBE\", \"params\": [\"%s@aggTrade\"], \"id\": 1}", SYMBOL.toLowerCase()));
        if (USE_ORDER_BOOK) {
            log.info("Subscribing depth stream...");
            send(String.format("{\"method\": \"SUBSCRIBE\", \"params\": [\"%s@depth@100ms\"], \"id\": 2}", SYMBOL.toLowerCase()));
        }

        updateReadyState(true);
        log.info("MarketDataWebSocket service started.");
    }

    @Override
    public void onMessage(String msg) {
        JSONObject message = new JSONObject(msg);

        if (message.has("e")) {
            String eventType = message.getString("e");
            switch (eventType) {
                case "aggTrade" -> tradeHandler.onMessage(message);
                case "depthUpdate" -> orderBookHandler.onMessage(message);
            }
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        log.info(String.format("MarketDataWebSocket closed (code: %d, reason: %s, remote: %s)", code, reason, remote));
        updateReadyState(false);

        if (code != 1000) {
            log.warn("Got close code != 1000 - scheduling MarketDataWebSocket reconnect...");
            taskManager.schedule(WEBSOCKET_UNEXPECTED_RECONNECT_TASK_KEY, this::reconnect, 10, TimeUnit.SECONDS);
        }
    }

    @Override
    public void onError(Exception e) {
        log.error("MarketDataWebSocket encountered an error", e);
    }

    @Override
    public void notifyOrderBookStateUpdate(boolean ready) {
        if (USE_ORDER_BOOK)
            this.orderBookReady.set(ready);

        updateReadyState(this.ready.get() && this.orderBookReady.get());
    }

    private void updateReadyState(final boolean ready) {
        if (this.ready.compareAndSet(!ready, ready)) {
            log.info(String.format("MarketDataWebSocket state changed: %s", ready));
            callbacks.parallelStream().forEach(callback ->
                    callback.notifyMarketDataWSStateChanged(ready));
        } else {
            log.info(String.format("MarketDataWebSocket state unchanged: %s", ready));
        }
    }

    @Override
    public void reconnect() {
        log.info("Reconnecting MarketDataWebSocket...");
        updateReadyState(false);

        taskManager.cancel(WEBSOCKET_PING_TASK_KEY);
        tradeHandler.cancelTasks();
        super.reconnect();
        log.info("MarketDataWebSocket reconnect scheduled.");
    }

    private void ping() {
        log.info("Sending MarketDataWebSocket ping...");
        this.sendPing();
    }

    public boolean getReady() {
        return ready.get();
    }

    public boolean getOrderBookReady() {
        return orderBookReady.get();
    }

    public void addCallback(MarketDataWebSocketCallback callback) {
        this.callbacks.add(callback);
        log.info(String.format("Callback added: %s", callback.getClass().getName()));
    }

    public void removeCallback(MarketDataWebSocketCallback callback) {
        this.callbacks.remove(callback);
        log.info(String.format("Callback removed: %s", callback.getClass().getName()));
    }

    public void logAll() {
        try {
            log.debug(String.format("""
                            MarketDataWebSocket State:
                            symbol: %s
                            IsOpen: %s
                            orderBookReady: %s
                            ready: %s
                            """,
                    SYMBOL, this.isOpen(), orderBookReady.get(), ready.get()));
        } catch (Exception e) {
            log.warn("Failed to write", e);
        }
    }
}
