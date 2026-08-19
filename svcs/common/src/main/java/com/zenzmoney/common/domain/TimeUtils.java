package com.zenzmoney.common.domain;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
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

    /** First instant of {@code date} in {@code zone}, as epoch millis. */
    public static long startOfDay(LocalDate date, ZoneId zone) {
        return date.atStartOfDay(zone).toInstant().toEpochMilli();
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

    /**
     * First instant of {@code month} in {@code zone}, as epoch millis. The monthly
     * position (§1.10) is a half-open window {@code [startOfMonth(m), startOfMonth(m+1))},
     * so this is the only boundary function it needs — pass the next month for the
     * upper bound and a transaction on the stroke of midnight lands in exactly one
     * month rather than both or neither.
     */
    public static long startOfMonth(YearMonth month, ZoneId zone) {
        return month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
    }

    /** The calendar month {@code millis} falls in, as seen from {@code zone}. */
    public static YearMonth monthOf(long millis, ZoneId zone) {
        return YearMonth.from(Instant.ofEpochMilli(millis).atZone(zone));
    }

    public static long startOfYear(Year year, ZoneId zone) {
        return year.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
    }

    public static Year yearOf(long millis, ZoneId zone) {
        return Year.from(Instant.ofEpochMilli(millis).atZone(zone));
    }

    public static ZoneId zoneOrUtc(String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException | NullPointerException e) {
            return ZoneId.of("UTC");
        }
    }
}
