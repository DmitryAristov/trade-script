package org.tradebot;

import org.tradebot.service.TradingBot;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.tradebot.binance.WebSocketService.reconnectsCount;

public class Main {

    public static void main(String[] args) {
        TradingBot bot = TradingBot.createBot("BTCUSDT", 1);
        bot.start();

        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleAtFixedRate(() -> {
            if (reconnectsCount > 100) {
                TradingBot.getInstance().exit("100 reconnects exceed");
            }
        }, 1, 1, TimeUnit.MINUTES);

        ScheduledExecutorService executor2 = Executors.newSingleThreadScheduledExecutor();
        executor2.schedule(() -> TradingBot.getInstance().exit("time exceed"), 2, TimeUnit.MINUTES);
    }
}
