package org.tradebot.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public class TimeFormatter {
    public static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static String format(long mills) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(mills), ZoneOffset.UTC).format(DATETIME_FORMATTER);
    }

    public static String format(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC).format(DATETIME_FORMATTER);
    }

    public static String format(Instant instant, ZoneOffset zoneOffset) {
        return LocalDateTime.ofInstant(instant, zoneOffset).format(DATETIME_FORMATTER);
    }

    public static String format(LocalDateTime localDateTime) {
        return localDateTime.format(DATETIME_FORMATTER);
    }

    public static String now() {
        return LocalDateTime.now().format(DATETIME_FORMATTER);
    }
}
