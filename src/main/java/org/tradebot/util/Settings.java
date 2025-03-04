package org.tradebot.util;

import org.tradebot.domain.Precision;

import java.text.SimpleDateFormat;

public class Settings {


    //APPLICATION BASE SETTINGS


    public static final boolean TEST_RUN = true;
    public static final boolean SIMULATE_API_ERRORS = true;
    public static final int API_ERROR_REPEATING_COUNT = 10;
    public static final boolean SIMULATE_RECONNECT_WS = true;
    public static final boolean SIMULATE_WEB_SOCKET_LOST_MESSAGES = false;
    public static final boolean USE_ORDER_BOOK = false;


    //TRADE SETTINGS


    //account settings
    public static final Precision DEFAULT_PRECISION = new Precision(1, 2);
    public static final double RISK_LEVEL;

    // strategy params
    public static final String SYMBOL = "BTCUSDT";
    public static final int LEVERAGE = TEST_RUN ? 1 : 25;

    public static final double[] TAKE_PROFIT_THRESHOLDS = new double[]{0.5, 0.75};
    public static final double STOP_LOSS_MULTIPLIER = 0.02;
    public static final long POSITION_LIVE_TIME = 240; //minutes

    // imbalance service params
    public static final long DATA_LIVE_TIME = 10 * 60_000L;
    public static final long LARGE_DATA_LIVE_TIME = 60 * 60_000L;
    public static final long LARGE_DATA_ENTRY_SIZE = 15_000L;

    public static final double COMPLETE_TIME_MODIFICATOR = 0.5;
    public static final double POTENTIAL_COMPLETE_TIME_MODIFICATOR = 0.05;
    public static final double SPEED_MODIFICATOR = 1E-7;
    public static final double PRICE_MODIFICATOR = 0.02;
    public static final double MAX_VALID_IMBALANCE_PART = 0.2;

    public static final long MIN_IMBALANCE_TIME_DURATION = 10_000L;
    public static final long TIME_CHECK_CONTR_IMBALANCE = 60 * 60_000L;
    public static final long MIN_POTENTIAL_COMPLETE_TIME = 2_000L;
    public static final long MIN_COMPLETE_TIME = 60_000L;
    public static final double RETURNED_PRICE_IMBALANCE_PARTITION = 0.5;

    //volatility service params
    public static final long UPDATE_TIME_PERIOD_HOURS = 12;
    public static final int VOLATILITY_CALCULATE_PAST_TIME_DAYS = 1;
    public static final int AVERAGE_PRICE_CALCULATE_PAST_TIME_DAYS = 1;


    // TECHNICAL SETTINGS


    public static final long WEBSOCKET_RECONNECT_PERIOD;
    static {
        if (TEST_RUN && SIMULATE_RECONNECT_WS) {
            WEBSOCKET_RECONNECT_PERIOD = 5;
        } else {
            WEBSOCKET_RECONNECT_PERIOD = 24 * 60 - 5;
        }
    }

    public static final int MAX_TRADE_QUEUE_SIZE = 100000;


    //http and websocket settings
    public static final String WEB_SOCKET_URL;
    public static final String BASE_URL;
    public static final int RECV_WINDOW = 2000;
    public static final long TIME_DIFF = 0;

    static {
        if (TEST_RUN) {
            WEB_SOCKET_URL = "wss://stream.binancefuture.com/ws";
            BASE_URL = "https://testnet.binancefuture.com";
            RISK_LEVEL = 0.2;
        } else {
            WEB_SOCKET_URL = "wss://fstream.binance.com/ws";
            BASE_URL = "https://fapi.binance.com";
            RISK_LEVEL = 0.95;
        }
    }

    //retry attempts and duration between attempts
    public static final int RETRIES_COUNT = TEST_RUN ? 1 : 2;
    public static final int RETRY_SLEEP_TIME = 100;


    // CONSTANTS


    //orders client ID prefixes
    public static final String OPEN_POSITION_CLIENT_ID_KEY = "position_open_market";
    public static final String STOP_CLIENT_ID_KEY = "stop_market";
    public static final String TAKE_CLIENT_ID_PREFIX = "take_limit_";
    public static final String BREAK_EVEN_STOP_CLIENT_ID_KEY = "break_even_stop_market";
    public static final String TIMEOUT_CLOSE_CLIENT_ID_KEY = "timeout_stop_market";
    public static final String CLOSE_CLIENT_ID_PREFIX = "close_market_";

    //common task key prefixes
    public static final String WEBSOCKET_PING_TASK_KEY = "market_data_websocket_ping";
    public static final String WEBSOCKET_RECONNECT_TASK_KEY = "market_data_websocket_reconnect";
    public static final String WEBSOCKET_UNEXPECTED_RECONNECT_TASK_KEY = "market_data_websocket_reconnect_unexpected_code";
    public static final String VOLATILITY_UPDATE_TASK_KEY = "volatility_update";
    public static final String MARKET_DATA_UPDATE_TASK_KEY = "market_data_update";
    public static final String STATE_UPDATE_TASK_KEY = "state_update";
    public static final String BALANCE_UPDATE_TASK = "balance_update";
    public static final String WRITE_HTTP_ERROR_TASK = "write_http_error";

    //account specific task key prefixes
    public static final String USER_STREAM_PING_TASK_KEY = "user_stream_ping";
    public static final String USER_STREAM_RECONNECT_TASK_KEY = "user_stream_reconnect";
    public static final String USER_STREAM_UNEXPECTED_RECONNECT_TASK_KEY = "user_stream_reconnect_unexpected_code";

    public static final String CHECK_ORDERS_API_MODE_TASK_KEY = "check_orders_api";
    public static final String AUTOCLOSE_POSITION_TASK_KEY = "auto_close_position";
    public static final String HANDLE_OPEN_ORDER_FILLED_TASK_KEY = "open_order_filled";
    public static final String HANDLE_TAKE_0_ORDER_FILLED_TASK_KEY = "first_take_order_filled";
    public static final String HANDLE_UNKNOWN_ORDER_FILLED_TASK_KEY = "unknown_order_filled";
    public static final String HANDLE_CLOSE_ORDER_FILLED_TASK_KEY = "close_order_filled";
    public static final String HANDLE_CLOSE_POSITION_TASK_KEY = "close_position";


    //logging settings
    public static final String LOGS_DIR_PATH = System.getProperty("user.dir") + "/output/logs/";
    public static final String STATE_FILE_PATH = System.getProperty("user.dir") + "/output/state/";
    public static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd");

}
