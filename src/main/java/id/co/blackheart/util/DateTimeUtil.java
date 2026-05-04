package id.co.blackheart.util;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

public final class DateTimeUtil {

    private DateTimeUtil() {
    }

    public static long toEpochMillis(LocalDateTime dateTime) {
        if (dateTime == null) {
            throw new IllegalArgumentException("dateTime must not be null");
        }

        return dateTime
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
    }

    public static long toEpochMillis(LocalDateTime dateTime, ZoneId zoneId) {
        if (dateTime == null) {
            throw new IllegalArgumentException("dateTime must not be null");
        }
        if (zoneId == null) {
            throw new IllegalArgumentException("zoneId must not be null");
        }

        return dateTime
                .atZone(zoneId)
                .toInstant()
                .toEpochMilli();
    }

    public static long toEpochSeconds(LocalDateTime dateTime) {
        if (dateTime == null) {
            throw new IllegalArgumentException("dateTime must not be null");
        }

        return dateTime
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .getEpochSecond();
    }

    public static long toEpochSecondsUtc(LocalDateTime dateTime) {
        if (dateTime == null) {
            throw new IllegalArgumentException("dateTime must not be null");
        }

        return dateTime.toInstant(ZoneOffset.UTC).getEpochSecond();
    }

    public static long toEpochMillisUtc(LocalDateTime dateTime) {
        if (dateTime == null) {
            throw new IllegalArgumentException("dateTime must not be null");
        }

        return dateTime.toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}