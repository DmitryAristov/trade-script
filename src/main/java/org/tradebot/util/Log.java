package org.tradebot.util;


import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;



public class Log {

    public static final Log.Level LOG_LEVEL = Level.DEBUG;
    public static final ZoneOffset ZONE_OFFSET = ZoneOffset.of("+2");

    public enum Level {
        DEBUG,
        INFO,
        ERROR
    }

    public static final long PROGRESS_LOG_STEP = 15_000L;
    public static final String LOG_FILE_PATH = System.getProperty("user.dir") + "/src/main/resources/logs/output.log";

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

    public static void debug(String message, long mills) {
        log(message, Level.DEBUG, mills);
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
        writeLogFile(logEntry);
    }

    private static void writeLogFile(String logEntry) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(LOG_FILE_PATH, true))) {
            writer.write(logEntry);
            writer.newLine();
        } catch (IOException e) {
            throw Log.error(e);
        }
    }

    public static void logProgress(long startTime, AtomicLong step, double progress, String operationName) {
        if (progress == 0) {
            return;
        }
        if (Instant.now().toEpochMilli() - startTime > step.get()) {
            Instant approximateEndUTC = Instant.ofEpochMilli(startTime).plusMillis(
                    Math.round((Instant.now().toEpochMilli() - startTime) / progress));

            Log.info(String.format("operation '%s' progress %.2f%%. Will done ~ %s", operationName, progress * 100,
                    TimeFormatter.format(approximateEndUTC, ZONE_OFFSET)));

            step.addAndGet(PROGRESS_LOG_STEP);
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
