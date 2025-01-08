package org.tradebot.util;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;

public class Log {

    public enum Level {
        DEBUG,
        INFO,
        WARN,
        ERROR
    }

    public static final String LOGS_DIR_PATH = System.getProperty("user.dir") + "/src/main/resources/logs/";
    public static final String INFO_LOGS_PATH = LOGS_DIR_PATH + "info.log";
    public static final String DEBUG_LOGS_PATH = LOGS_DIR_PATH + "debug.log";
    public static final String MARKET_DATA_LOGS_PATH = LOGS_DIR_PATH + "market_data.log";
    public static final String ORDER_BOOK_LOGS_PATH = LOGS_DIR_PATH + "order_book.log";

    static {
        resetLogFile(INFO_LOGS_PATH);
        resetLogFile(DEBUG_LOGS_PATH);
        resetLogFile(MARKET_DATA_LOGS_PATH);
        resetLogFile(ORDER_BOOK_LOGS_PATH);
    }

    private static void resetLogFile(String path) {
        try (RandomAccessFile file = new RandomAccessFile(path, "rw")) {
            file.setLength(0);
        } catch (IOException _) {  }
    }

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
        return new RuntimeException(exception);
    }

    public static RuntimeException error(String message) {
        log("error got: " + message, Level.ERROR);
        return new RuntimeException(message);
    }

    public static void info(String message) {
        log(message, Level.INFO);
    }

    public static void info(String message, long mills) {
        log(message, Level.INFO, mills);
    }

    public static void warn(String message) {
        log(message, Level.WARN);
    }

    public static void warn(Exception exception) {
        log("exception got: " +
                        exception.getMessage() +
                        Arrays.stream(exception.getStackTrace())
                                .map(StackTraceElement::toString)
                                .reduce("", (s1, s2) -> s1 + "\n    at " + s2),
                Level.WARN);
    }

    private static void log(String message, Level level) {
        log(message, level, -1);
    }

    private static void log(String message, Level level, long mills) {
        String classAndMethodName = getClassAndMethod();
        String logEntry = level + ((level == Level.INFO) ? " " : "") + " [" + TimeFormatter.now() + "] " + classAndMethodName + message;

        if (mills != -1) {
            logEntry += " on " + TimeFormatter.format(mills) + " (" + mills + ")";
        }

        writeLogFile(logEntry, DEBUG_LOGS_PATH);

        if (level != Level.DEBUG){
            writeLogFile(logEntry, INFO_LOGS_PATH);
        }
    }

    private static void writeLogFile(String logEntry, String filePath) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath, true))) {
            writer.write(logEntry);
            writer.newLine();
        } catch (IOException e) {
            throw new RuntimeException(e);
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





    public static void info(String message, String path) {
        String classAndMethodName = getClassAndMethod();
        String logEntry = Level.INFO + " [" + TimeFormatter.now() + "] " + classAndMethodName + message;

        writeLogFile(logEntry, path);
    }

    public static void removeLines(int count, String path) {
        try (RandomAccessFile file = new RandomAccessFile(path, "rw")) {
            long length = file.length();
            int linesCount = 0;

            while (length > 0 && linesCount < count + 1) {
                length--;
                file.seek(length);
                if (file.readByte() == '\n') {
                    linesCount++;
                }
            }
            file.setLength(length + 1);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
