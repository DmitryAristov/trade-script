package org.tradebot;

import org.tradebot.service.TradingBot;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class Main {

    public static void main(String[] args) {
        TradingBot bot = TradingBot.createBot("BTCUSDT", 6);
        bot.start();

        ScheduledExecutorService executor = new ScheduledThreadPoolExecutor(1);
        executor.schedule(() -> {
            bot.stop();
            executor.shutdown();
        }, 5, TimeUnit.MINUTES);
    }
}
