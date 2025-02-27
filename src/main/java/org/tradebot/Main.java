package org.tradebot;

import org.tradebot.service.TradingBot;

public class Main {
    private final String command = "java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 " +
            "-classpath " +
            "/home/dmitriy/.m2/repository/org/trade-script/1.0.0/trade-script-1.0.0.jar:" +
            "/home/dmitriy/.m2/repository/org/json/json/20240303/json-20240303.jar:" +
            "/home/dmitriy/.m2/repository/org/java-websocket/Java-WebSocket/1.5.7/Java-WebSocket-1.5.7.jar:" +
            "/home/dmitriy/.m2/repository/org/slf4j/slf4j-api/2.0.6/slf4j-api-2.0.6.jar:" +
            "/home/dmitriy/.m2/repository/org/jetbrains/annotations/13.0/annotations-13.0.jar org.tradebot.Main";

    public static void main(String[] args) {
        TradingBot.getInstance().start();
    }
}
