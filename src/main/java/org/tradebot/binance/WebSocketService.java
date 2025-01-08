package org.tradebot.binance;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;
import org.tradebot.listener.WebSocketCallback;
import org.tradebot.util.Log;
import org.tradebot.util.TaskManager;

import java.net.URI;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.tradebot.service.ImbalanceService.fakeOpenTime;

public class WebSocketService extends WebSocketClient {

    private final TradeHandler tradeHandler;
    private final OrderBookHandler orderBookHandler;
    private final UserDataHandler userDataHandler;
    private final RestAPIService apiService;
    private final TaskManager taskManager;
    private final String symbol;
    private WebSocketCallback callback;

    private String listenKey = "";
    protected final AtomicBoolean isReady = new AtomicBoolean(false);

    public WebSocketService(String symbol,
                            TradeHandler tradeHandler,
                            OrderBookHandler orderBookHandler,
                            UserDataHandler userDataHandler,
                            RestAPIService apiService,
                            TaskManager taskManager) {
        super(URI.create("wss://stream.binancefuture.com/ws"));//"wss://fstream.binance.com/ws"
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
                this.sendPing();
            }
        }, TaskManager.Type.PERIOD, 5, 5, TimeUnit.MINUTES);

        taskManager.create("websocket_reconnect", this::reconnectWebSocket, TaskManager.Type.PERIOD,
                24 * 60 - 5, 24 * 60 - 5, TimeUnit.MINUTES);
        taskManager.create("fake_disconnect", this::fakeDisconnect, TaskManager.Type.PERIOD, 25, 25, TimeUnit.MILLISECONDS);
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        send(String.format("{\"method\": \"SUBSCRIBE\", \"params\": [\"%s@trade\"], \"id\": 1}", symbol.toLowerCase()));
        send(String.format("{\"method\": \"SUBSCRIBE\", \"params\": [\"%s@depth@100ms\"], \"id\": 2}", symbol.toLowerCase()));
        connectUserDataStream();
        setReady(true);

        Log.info("service started");
    }

    @Override
    public void onMessage(String msg) {
        JSONObject message = new JSONObject(msg);
        if (!isReady()) {
            return;
        }

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
        Log.info(String.format("websocket closed :: %d, %s", code, reason));
    }

    @Override
    public void onError(Exception e) {
        Log.warn(e);
    }

    protected void reconnectWebSocket() {
        Log.info("reconnecting websocket...");
        unsubscribeFromStreams();
        try { reconnectBlocking(); } catch (InterruptedException e) { Log.warn(e); }
    }

    public void stop() {
        if (!isReady()) {
            Log.warn("trying to stop already stopped service");
            return;
        }
        Log.info("stopping websocket...");
        unsubscribeFromStreams();
        close();
        Log.info("stopped");
    }

    private void unsubscribeFromStreams() {
        try {
            setReady(false);
            send(String.format("{\"method\": \"UNSUBSCRIBE\", \"params\": [\"%s@trade\"], \"id\": 1}", symbol.toLowerCase()));
            send(String.format("{\"method\": \"UNSUBSCRIBE\", \"params\": [\"%s@depth@100ms\"], \"id\": 2}", symbol.toLowerCase()));
            send(String.format("{\"method\": \"UNSUBSCRIBE\", \"params\": [\"%s\"], \"id\": 3}", listenKey));

            taskManager.stop("user_data_stream_ping");
            apiService.removeUserStreamKey();
            Log.info("user data stream stopped");
        } catch (Exception e) {
            Log.warn(e);
        }
    }

    public void connectUserDataStream() {
        listenKey = apiService.getUserStreamKey();
        send(String.format("{\"method\": \"SUBSCRIBE\", \"params\": [\"%s\"], \"id\": 3}", listenKey));

        taskManager.create("user_data_stream_ping", () -> {
            if (isReady()) {
                apiService.keepAliveUserStreamKey();
            }
        }, TaskManager.Type.PERIOD, 59, 59, TimeUnit.MINUTES);
    }

    public boolean isReady() {
        return isReady.get() && isOpen();
    }

    private void setReady(boolean ready) {
        if (isReady.get() == ready)
            return;
        isReady.set(ready);
        Log.info("websocket ready state changed :: " + ready);
        if (callback != null)
            callback.notifyWebsocketStateChanged(ready);
    }

    public void setCallback(WebSocketCallback callback) {
        this.callback = callback;
        Log.info(String.format("callback added %s", callback.getClass().getName()));
    }

    public void logAll() {
        Log.debug(String.format("""
                        symbol: %s
                        listenKey: %s
                        this.isOpen(): %s
                        this.isReady(): %s
                        """,
                symbol,
                listenKey,
                this.isOpen(),
                this.isReady()
        ));
    }

    public static int reconnectsCount = 0;
    private void fakeDisconnect() {
        long openTime = System.currentTimeMillis() - fakeOpenTime;
        if (openTime > -20L + reconnectsCount * 10L && openTime < 10L + reconnectsCount * 10L) {
            if (!isReady()) {
                return;
            }
            this.setReady(false);
            Log.info("<<<<FAKE>>>> fake disconnect for duration :: " + openTime);
            Log.info("<<<<FAKE>>>> current positions count :: " + reconnectsCount);
            Log.info("<<<<FAKE>>>> fake open time :: ", fakeOpenTime);
            long rerunTaskDelay = new Random().nextLong(1_000L, 30_000L);
            Log.info("<<<<FAKE>>>> reconnect after :: " + rerunTaskDelay);
            taskManager.create("reset_ws_ready_state", () -> {
                Log.info("<<<<FAKE>>>> reconnect is running..");
                this.setReady(true);
                reconnectsCount++;
                Log.info("<<<<FAKE>>>> reconnects count increased  :: " + reconnectsCount);
                fakeOpenTime = System.currentTimeMillis() + 120_000L;
                Log.info("<<<<FAKE>>>> next position open time :: ", fakeOpenTime);
            }, TaskManager.Type.DELAYED, rerunTaskDelay, -1, TimeUnit.MILLISECONDS);
        }
    }
}