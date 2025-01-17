package org.tradebot;

import org.tradebot.service.TradingBot;

public class Main {
    private final String command = "dmitriy@rogzephyrus:~/IdeaProjects/trade-script$ java -classpath /home/dmitriy/.m2/repository/org/trade-script/1.0-SNAPSHOT/trade-script-1.0-SNAPSHOT.jar" +
            ":/home/dmitriy/.m2/repository/org/json/json/20240303/json-20240303.jar:/home/dmitriy/.m2/repository/org/java-websocket/Java-WebSocket/1.5.7/Java-WebSocket-1.5.7.jar" +
            ":/home/dmitriy/.m2/repository/org/slf4j/slf4j-api/2.0.6/slf4j-api-2.0.6.jar org.tradebot.Main";

    public static void main(String[] args) {
        TradingBot bot = TradingBot.createBot("BTCUSDT", 10);
        bot.start();
    }
}
