package org.tradebot.util;

import org.tradebot.service.TradingBot;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;

public class Log {

    public enum Level {
        DEBUG,
        INFO,
        ERROR
    }

    public static final String INFO_LOGS_PATH = System.getProperty("user.dir") + "/src/main/resources/logs/info/output.log";
    public static final String FULL_LOGS_PATH = System.getProperty("user.dir") + "/src/main/resources/logs/all/output.log";

    public static void debug(String message) {
        log(message, Level.DEBUG);
    }

    public static RuntimeException error(Exception exception) {
        log("exception got: " +
                        exception.getMessage() +
                        Arrays.stream(exception.getStackTrace())
                                .map(StackTraceElement::toString)
                                .reduce("", (s1, s2) -> s1 + "\n    at " + s2),
                Level.ERROR);
        TradingBot.logAll();
        return new RuntimeException(exception);
    }

    public static RuntimeException error(String message) {
        log("error got: " + message, Level.ERROR);
        TradingBot.logAll();
        return new RuntimeException(message);
    }

    public static void info(String message) {
        log(message, Level.INFO);
    }

    public static void info(String message, long mills) {
        log(message, Level.INFO, mills);
    }

    private static void log(String message, Level level) {
        log(message, level, -1);
    }

    private static void log(String message, Level level, long mills) {
        String classAndMethodName = getClassAndMethod();
        String logEntry = level + ((level == Level.INFO) ? " " : "") + " [" + TimeFormatter.now() + "] " + classAndMethodName + message;

        if (mills != -1) {
            logEntry += " on " + TimeFormatter.format(mills);
        }

        writeLogFile(logEntry, FULL_LOGS_PATH);

        if (level != Level.DEBUG){
            writeLogFile(logEntry, INFO_LOGS_PATH);
        }
    }

    private static void writeLogFile(String logEntry, String filePath) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath, true))) {
            writer.write(logEntry);
            writer.newLine();
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    private static String getClassAndMethod() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        int i = 1;
        String fullClassName = stackTrace[i].getClassName();
        while (fullClassName.equals(Log.class.getName())) {
            i++;
            fullClassName = stackTrace[i].getClassName();
        }
        String methodName = stackTrace[i].getMethodName();
        String simpleClassName = fullClassName.substring(fullClassName.lastIndexOf('.') + 1);
        return simpleClassName + "." + methodName + " :::: ";
    }
}
