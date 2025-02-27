package org.tradebot.util;

import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;
import org.tradebot.domain.APIError;
import org.tradebot.domain.Position;
import org.tradebot.domain.TradingBotState;
import org.tradebot.service.TradingBot;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.File;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.Date;
import java.util.Objects;

import static org.tradebot.util.JsonParser.parseAPIError;
import static org.tradebot.util.JsonParser.parseException;
import static org.tradebot.util.Log.Level.*;
import static org.tradebot.util.Settings.*;

public class Log {

    public enum Level {
        DEBUG,
        INFO,
        WARN,
        ERROR
    }

    static {
        ensureLogDirectoryExists();
        ensureLogDirectoryExists(LOGS_DIR_PATH + "debug/");
        ensureLogDirectoryExists(LOGS_DIR_PATH + "info/");
    }

    private String path = null;
    private Integer clientNumber = null;

    public Log() {  }

    public Log(String path) {
        ensureLogDirectoryExists(LOGS_DIR_PATH + path);
        this.path = path;
    }

    public Log(int clientNumber) {
        ensureLogDirectoryExists(LOGS_DIR_PATH + "debug/" + clientNumber);
        ensureLogDirectoryExists(LOGS_DIR_PATH + "info/" + clientNumber);
        ensureLogDirectoryExists(STATE_FILE_PATH + clientNumber);
        this.clientNumber = clientNumber;
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

    public void warn(String message, Exception exception) {
        log(getThrowableMessage(message, exception), WARN);
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
        log(getThrowableMessage(message, exception), ERROR);
    }

    @NotNull
    private static String getThrowableMessage(String message, Exception exception) {
        return exception.getClass() + ": " + message + ", caused by :: " +
                exception.getMessage() +
                Arrays.stream(exception.getStackTrace())
                        .map(StackTraceElement::toString)
                        .reduce("", (s1, s2) -> s1 + "\n    at " + s2);
    }

    public void removeLines(int count) {
        String dateSuffix = "_" + DATE_FORMAT.format(new Date()) + ".log";
        try (RandomAccessFile file = new RandomAccessFile(LOGS_DIR_PATH + path + dateSuffix, "rw")) {
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
            warn("Failed to remove lines", e);
        }
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

        writeLogFile(logEntry, Objects.requireNonNullElse(path, "debug/"));

        if (level != DEBUG) {
            writeLogFile(logEntry, "info/");
        }
        if (level == ERROR) {
            TradingBot.getInstance().logAll();
        }
    }

    private void writeLogFile(String logEntry, String filePath) {
        String finalPath = LOGS_DIR_PATH + filePath;
        if (clientNumber != null) {
            finalPath += clientNumber + "/";
        }
        finalPath += DATE_FORMAT.format(new Date()) + ".log";

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(finalPath, true))) {
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

    private static void ensureLogDirectoryExists() {
        ensureLogDirectoryExists(LOGS_DIR_PATH);
    }

    private static void ensureLogDirectoryExists(String path) {
        File directory = new File(path);
        if (!directory.exists()) {
            boolean created = directory.mkdirs();
            if (!created) {
                throw new RuntimeException("Failed to create log directory: " + path);
            }
        }
    }

    public void updateState(TradingBotState state) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(STATE_FILE_PATH + clientNumber + "/state.json", false))) {
            writer.write(state.toString());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void writeHttpError(APIError apiError) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(STATE_FILE_PATH + clientNumber + "/api_error.json", false))) {
            writer.write(parseAPIError(apiError));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void writeHttpError(Exception e) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(STATE_FILE_PATH + clientNumber + "/api_error.json", false))) {
            writer.write(parseException(e));
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    public void writeAccountUpdateEvent(double balance, Position position) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(STATE_FILE_PATH + clientNumber + "/balance.json", false))) {
            JSONObject jsonObject = new JSONObject();
            if (position != null)
                jsonObject = new JSONObject(position);

            jsonObject.put("balance", balance);
            jsonObject.put("timestamp", TimeFormatter.now());
            writer.write(jsonObject.toString(4));
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }
}
