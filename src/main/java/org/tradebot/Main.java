package org.tradebot;

import org.tradebot.service.TradingBot;

public class Main {

    public static void main(String[] args) {
        TradingBot bot = new TradingBot("BTCUSDT", 6);
        bot.start();

        try {
            Thread.sleep(60000L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        TradingBot.logAll();
        bot.stop();
    }
}
