package org.tradebot.util;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Objects;

import static org.tradebot.util.Log.Level.*;

public class Log {

    public enum Level {
        DEBUG,
        INFO,
        WARN,
        ERROR
    }

    public static final String LOGS_DIR_PATH = System.getProperty("user.dir") + "/src/main/resources/logs/";
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd");

    static {
        ensureLogDirectoryExists();
        resetLogFiles();
    }

    private String path = null;

    public Log() {  }

    public Log(String path) {
        this.path = path;
    }

    public void debug(String message) {
        log(message, DEBUG);
    }

    public void debug(String message, long mills) {
        log(message, DEBUG, mills);
    }

    public void info(String message) {
        log(message, INFO);
    }

    public void info(String message, long mills) {
        log(message, INFO, mills);
    }

    public void warn(String message) {
        log(message, WARN);
    }

    public void error(String message) {
        log(message, ERROR);
    }

    public RuntimeException throwError(String message) {
        log(message, ERROR);
        return new RuntimeException(message);
    }

    public RuntimeException throwError(String message, Exception exception) {
        error(message, exception);
        return new RuntimeException(exception);
    }

    public void error(String message, Exception exception) {
        log(exception.getClass() + ": " + message + ", caused by :: " +
                        exception.getMessage() +
                        Arrays.stream(exception.getStackTrace())
                                .map(StackTraceElement::toString)
                                .reduce("", (s1, s2) -> s1 + "\n    at " + s2),
                ERROR);
    }

    private void log(String message, Level level) {
        log(message, level, -1);
    }

    private void log(String message, Level level, long mills) {
        String classAndMethodName = getClassAndMethod();
        String logEntry = "[" + TimeFormatter.now() + "] " + level + ((level == INFO || level == WARN) ? "  " : " ") + classAndMethodName + message;

        if (mills != -1) {
            logEntry += " on " + TimeFormatter.format(mills) + " (" + mills + ")";
        }

        writeLogFile(logEntry, Objects.requireNonNullElse(path, "debug"));

        if (level != DEBUG) {
            writeLogFile(logEntry, "info");
        }
    }

    private void writeLogFile(String logEntry, String filePath) {
        String dateSuffix = "_" + DATE_FORMAT.format(new Date()) + ".log";
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(LOGS_DIR_PATH + filePath + dateSuffix, true))) {
            writer.write(logEntry);
            writer.newLine();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String getClassAndMethod() {
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

    public static void resetLogFiles() {
        File directory = new File(LOGS_DIR_PATH);
        if (!directory.isDirectory()) {
            return;
        }

        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.isFile()) {
                resetLogFile(file.getAbsolutePath());
            }
        }
    }

    public static void resetLogFile(String path) {
        try (RandomAccessFile file = new RandomAccessFile(path, "rw")) {
            file.setLength(0);
        } catch (IOException _) {  }
    }

    public void removeLines(int count, String path) {
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

    private static void ensureLogDirectoryExists() {
        File directory = new File(LOGS_DIR_PATH);
        if (!directory.exists()) {
            boolean created = directory.mkdirs();
            if (!created) {
                throw new RuntimeException("Failed to create log directory: " + LOGS_DIR_PATH);
            }
        }
    }
}
