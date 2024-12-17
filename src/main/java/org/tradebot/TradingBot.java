package org.tradebot;


import org.tradebot.api_service.BinanceAPIService;
import org.tradebot.domain.MarketEntry;
import org.tradebot.listener.MarketDataListener;
import org.tradebot.service.*;
import org.tradebot.util.Log;

import java.net.URI;

public class TradingBot implements MarketDataListener {

    private final ImbalanceService imbalanceService;
    private final Strategy strategy;
    private final VolatilityService volatilityService;
    private final TradingAPI tradingAPI;

    public TradingBot(TradingAPI tradingAPI) {
        this.tradingAPI = tradingAPI;
        this.imbalanceService = new ImbalanceService();
        this.strategy = new Strategy(imbalanceService, tradingAPI);
        this.volatilityService = new VolatilityService(tradingAPI);
        volatilityService.subscribe(imbalanceService);
    }

    @Override
    public void notify(long timestamp, MarketEntry marketEntry) throws Exception {
        volatilityService.onTick(timestamp, marketEntry);
        imbalanceService.onTick(timestamp, marketEntry);
        strategy.onTick(timestamp, marketEntry);
    }

    public void start() {
        tradingAPI.subscribe(this);
    }

    public static void main(String[] args) {
        try {
            BinanceAPIService binanceAPI = new BinanceAPIService("BTCUSDC");
            TradingBot bot = new TradingBot(binanceAPI);

            binanceAPI.connect();
            bot.start();
        } catch (Exception e) {
            Log.debug(e, false);
        }
    }
}
