package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.TransactionType;
import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.common.exception.NotFoundException;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.TransactionRepository;
import com.zenzmoney.core.web.dto.MonthlySummaryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.YearMonth;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The monthly position (§1.10, F-1.2). These tests are mostly about the window:
 * the arithmetic is one subtraction, but a boundary that is off by an hour silently
 * moves a user's transactions into the wrong month.
 */
@ExtendWith(MockitoExtension.class)
class MonthlySummaryServiceTest {

    @Mock TransactionRepository transactionRepository;
    @Mock AccountService accountService;
    @Mock CurrentUserService currentUser;
    @InjectMocks MonthlySummaryService monthlySummaryService;

    /**
     * Default: the filter passes through unchanged, which is what AccountService does for a
     * null or owned id. Lenient because the malformed-month path throws before reaching it.
     */
    @BeforeEach
    void passThroughAccountFilter() {
        lenient().when(accountService.requireOwnedFilter(any(), any())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            return id == null || id.isBlank() ? null : id;
        });
    }

    private User user(String timezone) {
        User u = new User();
        u.setId("u1");
        u.setActiveCurrency("LKR");
        u.setTimezone(timezone);
        return u;
    }

    private void stubSums(long income, long expenses) {
        when(transactionRepository.sumAmountByTypeInWindow(
                eq("u1"), eq(TransactionType.INCOME), anyLong(), anyLong(), any())).thenReturn(income);
        when(transactionRepository.sumAmountByTypeInWindow(
                eq("u1"), eq(TransactionType.EXPENSE), anyLong(), anyLong(), any())).thenReturn(expenses);
    }



    @Test
    void position_isIncomeMinusExpenses() {
        when(currentUser.requireUser()).thenReturn(user("UTC"));
        stubSums(300_000, 125_000);

        MonthlySummaryResponse resp = monthlySummaryService.summary("2026-08", null);

        assertEquals(300_000, resp.getIncome());
        assertEquals(125_000, resp.getExpenses());
        assertEquals(175_000, resp.getPosition());
        assertEquals("LKR", resp.getCurrency());
        assertEquals("2026-08", resp.getMonth());
    }

    /** A month that ran at a deficit is a real answer, not an error to clamp at zero. */
    @Test
    void position_canBeNegative() {
        when(currentUser.requireUser()).thenReturn(user("UTC"));
        stubSums(50_000, 80_000);

        assertEquals(-30_000, monthlySummaryService.summary("2026-08", null).getPosition());
    }

    /** Nothing recorded is a position of zero — never a missing or carried-over figure. */
    @Test
    void emptyMonth_isZero_notCarriedForward() {
        when(currentUser.requireUser()).thenReturn(user("UTC"));
        stubSums(0, 0);

        MonthlySummaryResponse resp = monthlySummaryService.summary("2020-02", null);

        assertEquals(0, resp.getIncome());
        assertEquals(0, resp.getPosition());
    }

    @Test
    void window_isHalfOpen_fromMonthStartToNextMonthStart() {
        when(currentUser.requireUser()).thenReturn(user("UTC"));
        stubSums(0, 0);

        MonthlySummaryResponse resp = monthlySummaryService.summary("2026-08", null);

        ZoneId utc = ZoneId.of("UTC");
        long expectedFrom = YearMonth.of(2026, 8).atDay(1).atStartOfDay(utc).toInstant().toEpochMilli();
        long expectedTo = YearMonth.of(2026, 9).atDay(1).atStartOfDay(utc).toInstant().toEpochMilli();
        assertEquals(expectedFrom, resp.getFrom());
        assertEquals(expectedTo, resp.getTo());
        // Adjacent months must abut exactly: a gap loses transactions, an overlap counts them twice.
        assertEquals(expectedTo, YearMonth.of(2026, 9).atDay(1).atStartOfDay(utc).toInstant().toEpochMilli());
    }

    /**
     * Boundaries follow the user's zone, not the server's (OQ-2). Colombo is UTC+5:30,
     * so its August starts 5.5 hours before UTC's does.
     */
    @Test
    void window_followsTheUsersTimezone() {
        when(currentUser.requireUser()).thenReturn(user("Asia/Colombo"));
        stubSums(0, 0);

        MonthlySummaryResponse resp = monthlySummaryService.summary("2026-08", null);

        long utcStart = YearMonth.of(2026, 8).atDay(1).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli();
        assertEquals("Asia/Colombo", resp.getTimezone());
        assertEquals(utcStart - (5 * 60 + 30) * 60_000L, resp.getFrom());
    }

    /** A corrupt stored zone must degrade to UTC, not take the dashboard down. */
    @Test
    void unknownTimezone_fallsBackToUtc() {
        when(currentUser.requireUser()).thenReturn(user("Mars/Olympus"));
        stubSums(0, 0);

        assertEquals("UTC", monthlySummaryService.summary("2026-08", null).getTimezone());
    }

    @Test
    void omittedMonth_defaultsToTheUsersCurrentMonth() {
        when(currentUser.requireUser()).thenReturn(user("Asia/Colombo"));
        stubSums(0, 0);

        String expected = YearMonth.now(ZoneId.of("Asia/Colombo")).toString();
        assertEquals(expected, monthlySummaryService.summary(null, null).getMonth());
    }

    @Test
    void malformedMonth_rejected() {
        when(currentUser.requireUser()).thenReturn(user("UTC"));

        assertThrows(BadRequestException.class, () -> monthlySummaryService.summary("August 2026", null));
    }

    // --- account scoping: the home screen's account picker (F-1.1) ---

    /** Omitting the account spans every account the user holds — the position as §1.10 defines it. */
    @Test
    void omittedAccount_spansEveryAccount() {
        when(currentUser.requireUser()).thenReturn(user("UTC"));
        stubSums(300_000, 125_000);

        MonthlySummaryResponse resp = monthlySummaryService.summary("2026-08", null);

        assertNull(resp.getAccountId());
        verify(transactionRepository).sumAmountByTypeInWindow(
                eq("u1"), eq(TransactionType.INCOME), anyLong(), anyLong(), isNull());
    }

    @Test
    void accountId_narrowsTheSumsToThatAccount() {
        when(currentUser.requireUser()).thenReturn(user("UTC"));
        stubSums(300_000, 125_000);

        MonthlySummaryResponse resp = monthlySummaryService.summary("2026-08", "a1");

        assertEquals("a1", resp.getAccountId());
        verify(transactionRepository).sumAmountByTypeInWindow(
                eq("u1"), eq(TransactionType.INCOME), anyLong(), anyLong(), eq("a1"));
        verify(transactionRepository).sumAmountByTypeInWindow(
                eq("u1"), eq(TransactionType.EXPENSE), anyLong(), anyLong(), eq("a1"));
    }

    /**
     * An account the caller doesn't own is a 404, not a zeroed summary: "0.00" would be
     * a wrong answer rendered as a fact on the home screen.
     */
    @Test
    void unknownAccount_rejected() {
        when(currentUser.requireUser()).thenReturn(user("UTC"));
        when(accountService.requireOwnedFilter("nope", "u1"))
                .thenThrow(new NotFoundException("Account not found"));

        assertThrows(NotFoundException.class, () -> monthlySummaryService.summary("2026-08", "nope"));
    }
}
