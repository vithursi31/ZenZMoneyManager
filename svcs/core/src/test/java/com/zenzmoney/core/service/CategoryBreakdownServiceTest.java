package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.TransactionType;
import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.common.exception.NotFoundException;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.CategoryBreakdownRow;
import com.zenzmoney.core.repository.TransactionRepository;
import com.zenzmoney.core.web.dto.CategoryBreakdownResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The category breakdown (F-1.19). The aggregate itself is SQL's job — these tests
 * cover the split into directions, the totals derived from it, and the period rules.
 */
@ExtendWith(MockitoExtension.class)
class CategoryBreakdownServiceTest {

    @Mock TransactionRepository transactionRepository;
    @Mock AccountService accountService;
    @Mock CurrentUserService currentUser;
    @InjectMocks CategoryBreakdownService breakdownService;

    @BeforeEach
    void passThroughAccountFilter() {
        lenient().when(accountService.requireOwnedFilter(any(), any())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            return id == null || id.isBlank() ? null : id;
        });
    }

    private User user() {
        User u = new User();
        u.setId("u1");
        u.setActiveCurrency("LKR");
        u.setTimezone("UTC");
        return u;
    }

    private CategoryBreakdownRow row(String id, String name, TransactionType type, long amount, long count) {
        return new CategoryBreakdownRow(id, name, null, "#fff", "icon", type, amount, count);
    }

    private void stubRows(CategoryBreakdownRow... rows) {
        when(transactionRepository.categoryTotalsInWindow(eq("u1"), anyLong(), anyLong(), any()))
                .thenReturn(List.of(rows));
    }

    @Test
    void splitsCategoriesByDirection_andTotalsEachSide() {
        when(currentUser.requireUser()).thenReturn(user());
        stubRows(
                row("c-salary", "Salary", TransactionType.INCOME, 500_000, 1),
                row("c-side", "Freelance", TransactionType.INCOME, 75_000, 2),
                row("c-rent", "Rent", TransactionType.EXPENSE, 120_000, 1),
                row("c-food", "Food", TransactionType.EXPENSE, 45_005, 9));

        CategoryBreakdownResponse resp = breakdownService.breakdown("2026-08-01", "2026-08-31", null);

        assertEquals(575_000, resp.getIncome().getTotal());
        assertEquals(165_005, resp.getExpenses().getTotal());
        assertEquals(2, resp.getIncome().getCategories().size());
        assertEquals(2, resp.getExpenses().getCategories().size());
        assertEquals("Salary", resp.getIncome().getCategories().get(0).getName());
        assertEquals(9, resp.getExpenses().getCategories().get(1).getTransactionCount());
    }

    @Test
    void position_isIncomeMinusExpenses_andMayBeNegative() {
        when(currentUser.requireUser()).thenReturn(user());
        stubRows(
                row("c-salary", "Salary", TransactionType.INCOME, 50_000, 1),
                row("c-rent", "Rent", TransactionType.EXPENSE, 80_000, 1));

        assertEquals(-30_000, breakdownService.breakdown("2026-08-01", "2026-08-31", null).getPosition());
    }

    /** The query orders by amount descending; the split must not reshuffle it. */
    @Test
    void categoriesKeepBiggestFirstOrdering() {
        when(currentUser.requireUser()).thenReturn(user());
        stubRows(
                row("c-rent", "Rent", TransactionType.EXPENSE, 120_000, 1),
                row("c-food", "Food", TransactionType.EXPENSE, 45_000, 9),
                row("c-bus", "Transport", TransactionType.EXPENSE, 3_000, 4));

        List<CategoryBreakdownResponse.CategoryAmount> expenses =
                breakdownService.breakdown("2026-08-01", "2026-08-31", null).getExpenses().getCategories();

        assertEquals(List.of("Rent", "Food", "Transport"),
                expenses.stream().map(CategoryBreakdownResponse.CategoryAmount::getName).toList());
    }

    /** A period with nothing in it is zeros and empty lists — never null, never an error. */
    @Test
    void emptyPeriod_isZeroTotalsAndEmptyLists() {
        when(currentUser.requireUser()).thenReturn(user());
        stubRows();

        CategoryBreakdownResponse resp = breakdownService.breakdown("2020-02-01", "2020-02-29", null);

        assertEquals(0, resp.getIncome().getTotal());
        assertEquals(0, resp.getPosition());
        assertTrue(resp.getIncome().getCategories().isEmpty());
        assertTrue(resp.getExpenses().getCategories().isEmpty());
    }

    /** endDate is inclusive: the window's exclusive upper bound is the next day's start. */
    @Test
    void windowCoversTheWholeOfEndDate() {
        when(currentUser.requireUser()).thenReturn(user());
        stubRows();

        CategoryBreakdownResponse resp = breakdownService.breakdown("2026-08-01", "2026-08-31", null);

        ZoneId utc = ZoneId.of("UTC");
        long expectedFrom = java.time.LocalDate.of(2026, 8, 1).atStartOfDay(utc).toInstant().toEpochMilli();
        long expectedTo = java.time.LocalDate.of(2026, 9, 1).atStartOfDay(utc).toInstant().toEpochMilli();
        assertEquals(expectedFrom, resp.getFrom());
        assertEquals(expectedTo, resp.getTo());
    }

    @Test
    void accountId_isPassedToTheAggregate_andEchoed() {
        when(currentUser.requireUser()).thenReturn(user());
        stubRows();

        CategoryBreakdownResponse resp = breakdownService.breakdown("2026-08-01", "2026-08-31", "a2");

        assertEquals("a2", resp.getAccountId());
        verify(transactionRepository).categoryTotalsInWindow(eq("u1"), anyLong(), anyLong(), eq("a2"));
    }

    @Test
    void omittedAccount_spansEveryAccount() {
        when(currentUser.requireUser()).thenReturn(user());
        stubRows();

        breakdownService.breakdown("2026-08-01", "2026-08-31", null);

        verify(transactionRepository).categoryTotalsInWindow(eq("u1"), anyLong(), anyLong(), isNull());
    }

    /** A report is always over a period — an open-ended one is refused rather than scanning everything. */
    @Test
    void bothDatesAreRequired() {
        when(currentUser.requireUser()).thenReturn(user());

        assertThrows(BadRequestException.class,
                () -> breakdownService.breakdown(null, "2026-08-31", null));
        assertThrows(BadRequestException.class,
                () -> breakdownService.breakdown("2026-08-01", null, null));
    }

    @Test
    void malformedDate_rejected() {
        when(currentUser.requireUser()).thenReturn(user());

        assertThrows(BadRequestException.class,
                () -> breakdownService.breakdown("01/08/2026", "2026-08-31", null));
    }

    @Test
    void startAfterEnd_rejected() {
        when(currentUser.requireUser()).thenReturn(user());

        assertThrows(BadRequestException.class,
                () -> breakdownService.breakdown("2026-08-31", "2026-08-01", null));
    }

    @Test
    void unknownAccount_rejected() {
        when(currentUser.requireUser()).thenReturn(user());
        when(accountService.requireOwnedFilter("nope", "u1"))
                .thenThrow(new NotFoundException("Account not found"));

        assertThrows(NotFoundException.class,
                () -> breakdownService.breakdown("2026-08-01", "2026-08-31", "nope"));
    }
}
