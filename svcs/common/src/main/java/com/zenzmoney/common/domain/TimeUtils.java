package com.zenzmoney.common.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

public final class TimeUtils {

    private TimeUtils() {}

    public static long now() {
        return System.currentTimeMillis();
    }

    public static long startOfDay(long millis) {
        return Instant.ofEpochMilli(millis)
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli();
    }

    public static long startOfDay(long millis, ZoneId zone) {
        return Instant.ofEpochMilli(millis)
                .atZone(zone)
                .toLocalDate()
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli();
    }

    public static long daysAgo(int days) {
        return LocalDate.now(ZoneOffset.UTC)
                .minusDays(days)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli();
    }

    public static long startOfWeek() {
        return LocalDate.now(ZoneOffset.UTC)
                .with(DayOfWeek.MONDAY)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli();
    }

    public static long startOfMonth() {
        return LocalDate.now(ZoneOffset.UTC)
                .withDayOfMonth(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli();
    }
}
