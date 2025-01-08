package org.tradebot;

import org.tradebot.service.TradingBot;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.tradebot.binance.WebSocketService.reconnectsCount;

public class Main {

    public static void main(String[] args) {
        TradingBot bot = TradingBot.createBot("BTCUSDT", 1);
        bot.start();

        ScheduledExecutorService executor = new ScheduledThreadPoolExecutor(1);
        executor.scheduleAtFixedRate(() -> {
            if (reconnectsCount > 100) {
                TradingBot.exit("100 reconnects exceed");
            }
        }, 1, 1, TimeUnit.MINUTES);

        ScheduledExecutorService executor2 = new ScheduledThreadPoolExecutor(1);
        executor2.schedule(() -> TradingBot.exit("time exceed"), 3, TimeUnit.HOURS);
    }
}
