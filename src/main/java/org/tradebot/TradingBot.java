package org.tradebot;

import org.tradebot.binance.RestAPIService;
import org.tradebot.binance.OrderBookHandler;
import org.tradebot.binance.UserDataHandler;
import org.tradebot.binance.WebSocketService;
import org.tradebot.binance.TradeHandler;
import org.tradebot.domain.AccountInfo;
import org.tradebot.service.ImbalanceService;
import org.tradebot.service.VolatilityService;

public class TradingBot {
    public static final int LEVERAGE = 6;
    public static final String SYMBOL = "BTCUSDC";

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
            throw new RuntimeException("account cannot trade");
        }

        double balance = accountInfo.availableBalance();
        if (balance <= 0) {
            throw new RuntimeException("no available balance to trade");
        }

        apiService.setLeverage(LEVERAGE);
        if (apiService.getLeverage() != LEVERAGE) {
            throw new RuntimeException("leverage is incorrect");
        }

        this.imbalanceService = new ImbalanceService();
        this.strategy = new Strategy(apiService, webSocketService);
        this.imbalanceService.subscribe(strategy);

        this.volatilityService = new VolatilityService(apiService);
        this.volatilityService.subscribe(imbalanceService);

        this.tradeHandler = new TradeHandler();
        this.tradeHandler.subscribe(imbalanceService);

        this.orderBookHandler = new OrderBookHandler(apiService);
        this.orderBookHandler.subscribe(strategy);

        this.userDataStreamHandler = new UserDataHandler();
        this.userDataStreamHandler.subscribe(strategy);


        this.webSocketService = new WebSocketService(tradeHandler, orderBookHandler, userDataStreamHandler, apiService);
        webSocketService.connect();
    }

    public void stop() {
        this.imbalanceService.unsubscribe(strategy);
        this.volatilityService.unsubscribe(imbalanceService);
        this.tradeHandler.unsubscribe(imbalanceService);
        this.orderBookHandler.unsubscribe(strategy);
        this.userDataStreamHandler.unsubscribe(strategy);
        this.webSocketService.unsubscribe();
        this.webSocketService.close();
    }
}
