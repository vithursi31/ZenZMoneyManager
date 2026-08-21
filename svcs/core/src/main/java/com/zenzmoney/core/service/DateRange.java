package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.TimeUtils;
import com.zenzmoney.common.exception.BadRequestException;

import com.zenzmoney.common.i18n.Msg;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * A half-open millisecond window {@code [from, to)} parsed from inclusive ISO
 * {@code yyyy-MM-dd} bounds in the caller's zone. Either bound may be null, which
 * leaves that side unbounded.
 *
 * <p>Shared rather than copied: a second implementation of this arithmetic is how a
 * transaction list and the total printed above it come to disagree by one day.
 */
public record DateRange(Long from, Long to) {

    public static DateRange of(String startDate, String endDate, ZoneId zone) {
        LocalDate start = parse(startDate, "startDate");
        LocalDate end = parse(endDate, "endDate");
        if (start != null && end != null && start.isAfter(end)) {
            throw new BadRequestException(Msg.DATE_RANGE_INVERTED);
        }
        // An inclusive endDate becomes the start of the following day, so the window
        // matches the monthly position's [month start, next month start) rule (§1.10).
        return new DateRange(
                start == null ? null : TimeUtils.startOfDay(start, zone),
                end == null ? null : TimeUtils.startOfDay(end.plusDays(1), zone));
    }

    /** The same window, but with both bounds required — a report is always over a period. */
    public static DateRange required(String startDate, String endDate, ZoneId zone) {
        if (startDate == null || startDate.isBlank() || endDate == null || endDate.isBlank()) {
            throw new BadRequestException(Msg.DATE_RANGE_REQUIRED);
        }
        return of(startDate, endDate, zone);
    }

    private static LocalDate parse(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new BadRequestException(field + " must be in yyyy-MM-dd format, e.g. 2026-08-01.");
        }
    }
}
