package org.tradebot;

import org.tradebot.service.TradingBot;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Main {

    public static void main(String[] args) {
        TradingBot bot = new TradingBot("BTCUSDT", 6);
        bot.start();

        ScheduledExecutorService dailyRerunTaskScheduler = Executors.newScheduledThreadPool(1);
        dailyRerunTaskScheduler.scheduleAtFixedRate(() -> {
            bot.stop();

            try {
                Thread.sleep(Duration.ofMinutes(1));
            } catch (InterruptedException _) {  }

            bot.start();
        }, 24 * 60 -  5, 24 * 60 -  5, TimeUnit.MINUTES);
    }
}
