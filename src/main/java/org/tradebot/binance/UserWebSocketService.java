package org.tradebot.binance;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;
import org.tradebot.domain.HTTPResponse;
import org.tradebot.listener.UserWebSocketCallback;
import org.tradebot.service.TaskManager;
import org.tradebot.util.Log;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.tradebot.util.Settings.*;

public class UserWebSocketService extends WebSocketClient {

    private final Log log;
    private final UserDataHandler userDataHandler;
    private final APIService apiService;
    private final TaskManager taskManager;
    // from 0
    private final int clientNumber;
    private String listenKey = null;

    private final AtomicBoolean ready = new AtomicBoolean(false);
    private UserWebSocketCallback callback;

    public UserWebSocketService(UserDataHandler userDataHandler,
                                HttpClient httpClient,
                                int clientNumber) {
        super(URI.create(WEB_SOCKET_URL));
        this.userDataHandler = userDataHandler;
        this.apiService = new APIService(httpClient, clientNumber);
        this.clientNumber = clientNumber;
        this.taskManager = TaskManager.getInstance(clientNumber);
        this.log = new Log(clientNumber);

        log.info("UserStream initialized.");
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        log.info("UserStream connection opened.");

        HTTPResponse<String> response = apiService.getUserStreamKey();
        if (response.isSuccess()) {
            listenKey = response.getValue();
            log.debug(String.format("Acquired listenKey: %s", listenKey));
            if (listenKey != null) {
                log.info("Subscribing user stream...");
                send(String.format("{\"method\": \"SUBSCRIBE\", \"params\": [\"%s\"], \"id\": %d}", listenKey, clientNumber + 3));
                taskManager.scheduleAtFixedRate(USER_STREAM_PING_TASK_KEY, this::ping, 59, 59, TimeUnit.MINUTES);
            }

            taskManager.schedule(USER_STREAM_RECONNECT_TASK_KEY, this::reconnect, WEBSOCKET_RECONNECT_PERIOD, TimeUnit.MINUTES);
            updateReadyState(true);
            log.info("Opened user stream.");
        } else {
            taskManager.schedule(USER_STREAM_UNEXPECTED_RECONNECT_TASK_KEY, this::reconnect, 5, TimeUnit.SECONDS);
        }
    }

    @Override
    public void onMessage(String msg) {
        JSONObject message = new JSONObject(msg);
        if (message.has("e")) {
            userDataHandler.onMessage(message.getString("e"), message);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        log.info(String.format("UserStream closed (code: %d, reason: %s, remote: %s)", code, reason, remote));
        updateReadyState(false);

        if (code != 1000) {
            log.warn("Got close code != 1000 - scheduling UserStream reconnect...");
            taskManager.schedule(USER_STREAM_UNEXPECTED_RECONNECT_TASK_KEY, this::reconnect, 5, TimeUnit.SECONDS);
        }
    }

    @Override
    public void onError(Exception e) {
        log.error("UserStream encountered an error", e);
    }

    @Override
    public void reconnect() {
        log.info("Reconnecting UserStream...");
        updateReadyState(false);

        taskManager.cancel(USER_STREAM_PING_TASK_KEY);
        super.reconnect();
        log.info("UserStream reconnect scheduled.");
    }

    private void updateReadyState(final boolean ready) {
        if (this.ready.compareAndSet(!ready, ready)) {
            log.info(String.format("UserStream state changed: %s", ready));
            if (callback != null)
                callback.notifyUserDataWSChanged(ready);
        } else {
            log.info(String.format("UserStream state unchanged: %s", ready));
        }
    }

    private void ping() {
        log.info("Sending user stream ping...");
        HTTPResponse<String> response = apiService.keepAliveUserStreamKey();
        if (response.isError() && response.getError().code() == -1125) {
            log.warn("Got error 'This listenKey does not exist.', recreating...");
            taskManager.schedule(USER_STREAM_UNEXPECTED_RECONNECT_TASK_KEY, this::reconnect, 5, TimeUnit.SECONDS);
        }
    }

    public void setCallback(UserWebSocketCallback callback) {
        this.callback = callback;
        log.info(String.format("Callback set: %s", callback.getClass().getName()));
    }

    public AtomicBoolean getReady() {
        return ready;
    }

    public void logAll() {
        try {
            log.debug(String.format("""
                            UserStream state:
                            IsOpen: %s
                            listenKey: %s
                            ready: %s
                            """,
                    this.isOpen(), listenKey, ready.get()));
        } catch (Exception e) {
            log.warn("Failed to write", e);
        }
    }
}
