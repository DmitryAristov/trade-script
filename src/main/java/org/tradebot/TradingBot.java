package org.tradebot;

import org.tradebot.binance.RestAPIService;
import org.tradebot.binance.OrderBookHandler;
import org.tradebot.binance.UserDataHandler;
import org.tradebot.binance.WebSocketService;
import org.tradebot.binance.TradeHandler;
import org.tradebot.domain.AccountInfo;
import org.tradebot.service.ImbalanceService;
import org.tradebot.service.VolatilityService;
import org.tradebot.util.Log;

public class TradingBot {
    public static final int LEVERAGE = 6;
    public static final String SYMBOL = "BTCUSDT";

    private final RestAPIService apiService = new RestAPIService();
    private ImbalanceService imbalanceService;
    private Strategy strategy;
    private VolatilityService volatilityService;
    private WebSocketService webSocketService;
    private TradeHandler tradeHandler;
    private OrderBookHandler orderBookHandler;
    private UserDataHandler userDataStreamHandler;

    public void start() {
        AccountInfo accountInfo = apiService.getAccountInfo();
        if (!accountInfo.canTrade()) {
            throw Log.error("account cannot trade");
        }

        double balance = accountInfo.availableBalance();
        if (balance <= 0) {
            throw Log.error("no available balance to trade");
        }

        apiService.setLeverage(LEVERAGE);
        if (apiService.getLeverage() != LEVERAGE) {
            throw Log.error("leverage is incorrect");
        }

        imbalanceService = new ImbalanceService();
        strategy = new Strategy(apiService, webSocketService);
        imbalanceService.subscribe(strategy);

        volatilityService = new VolatilityService(apiService);
        volatilityService.subscribe(imbalanceService);
        volatilityService.start();

        tradeHandler = new TradeHandler();
        tradeHandler.subscribe(imbalanceService);

        orderBookHandler = new OrderBookHandler(apiService);
        orderBookHandler.subscribe(strategy);

        userDataStreamHandler = new UserDataHandler();
        userDataStreamHandler.subscribe(strategy);


        webSocketService = new WebSocketService(tradeHandler, orderBookHandler, userDataStreamHandler, apiService);
        webSocketService.connect();
    }

    public void stop() {
        imbalanceService.unsubscribe(strategy);
        volatilityService.unsubscribe(imbalanceService);
        volatilityService.stop();
        tradeHandler.unsubscribe(imbalanceService);
        orderBookHandler.unsubscribe(strategy);
        userDataStreamHandler.unsubscribe(strategy);
        webSocketService.unsubscribe();
        webSocketService.close();
    }
}
