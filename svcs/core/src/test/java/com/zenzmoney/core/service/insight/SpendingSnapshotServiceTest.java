package com.zenzmoney.core.service.insight;

import com.zenzmoney.common.domain.CategoryKind;
import com.zenzmoney.common.domain.TransactionType;
import com.zenzmoney.core.entity.Category;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.CategoryRepository;
import com.zenzmoney.core.repository.CategoryTotal;
import com.zenzmoney.core.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * These are the numbers a language model will read back to the user as fact, so the
 * arithmetic has to be the ledger's own — same half-open month windows as the
 * monthly position, same timezone, no rounding anywhere.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SpendingSnapshotServiceTest {

    private static final ZoneId COLOMBO = ZoneId.of("Asia/Colombo");   // UTC+5:30

    @Mock TransactionRepository transactionRepository;
    @Mock CategoryRepository categoryRepository;
    @InjectMocks SpendingSnapshotService service;

    @Test
    void buildsTheMonthInProgressAndTheOneBeforeIt() {
        when(categoryRepository.findByUserId("u1")).thenReturn(List.of(
                category("c-food", "Food & Drinks"), category("c-fuel", "Fuel")));
        when(transactionRepository.sumAmountByTypeInWindow(anyString(), eq(TransactionType.INCOME), anyLong(), anyLong()))
                .thenReturn(300_000L);
        when(transactionRepository.sumAmountByTypeInWindow(anyString(), eq(TransactionType.EXPENSE), anyLong(), anyLong()))
                .thenReturn(120_000L);
        when(transactionRepository.sumExpenseByCategoryInWindowGrouped(anyString(), anyLong(), anyLong()))
                .thenReturn(List.of(new CategoryTotal("c-food", 80_000L), new CategoryTotal("c-fuel", 40_000L)));

        SpendingSnapshot snapshot = service.snapshotFor(user("USD", "Asia/Colombo"));

        assertEquals(2, snapshot.getMonths().size());
        assertEquals("USD", snapshot.getCurrency());
        assertEquals("Asia/Colombo", snapshot.getTimezone());

        SpendingSnapshot.MonthSpend current = snapshot.getMonths().get(0);
        assertEquals(YearMonth.now(COLOMBO).toString(), current.getMonth(),
                "\"this month\" is the user's month, not the server's");
        assertEquals(YearMonth.now(COLOMBO).minusMonths(1).toString(), snapshot.getMonths().get(1).getMonth());
        assertEquals(180_000L, current.getPosition(), "income − expenses, in minor units");
        assertEquals(List.of("Food & Drinks", "Fuel"),
                current.getCategories().stream().map(SpendingSnapshot.CategorySpend::getName).toList());
        assertEquals(80_000L, current.getCategories().get(0).getAmount());
    }

    /**
     * Half-open {@code [from, to)} on the same boundary the monthly position uses, so
     * a figure the assistant quotes and the one on the dashboard are the same number.
     */
    @Test
    void asksForHalfOpenMonthWindowsInTheUsersZone() {
        when(categoryRepository.findByUserId("u1")).thenReturn(List.of());

        service.snapshotFor(user("USD", "Asia/Colombo"));

        ArgumentCaptor<Long> from = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> to = ArgumentCaptor.forClass(Long.class);
        verify(transactionRepository, org.mockito.Mockito.atLeastOnce())
                .sumExpenseByCategoryInWindowGrouped(eq("u1"), from.capture(), to.capture());

        YearMonth thisMonth = YearMonth.now(COLOMBO);
        assertEquals(thisMonth.atDay(1).atStartOfDay(COLOMBO).toInstant().toEpochMilli(),
                from.getAllValues().get(0));
        assertEquals(thisMonth.plusMonths(1).atDay(1).atStartOfDay(COLOMBO).toInstant().toEpochMilli(),
                to.getAllValues().get(0));
        assertEquals(from.getAllValues().get(0), to.getAllValues().get(1),
                "the previous month ends exactly where this one begins — no gap, no overlap");
    }

    @Test
    void fallsBackToUtcForAUserWithNoTimezone() {
        when(categoryRepository.findByUserId("u1")).thenReturn(List.of());

        assertEquals("UTC", service.snapshotFor(user("USD", null)).getTimezone());
    }

    /**
     * A category deleted after its transactions were recorded leaves the spend real
     * and the label gone. Dropping the row would make the category lines stop adding
     * up to the month's total, and the model would quote a breakdown that is short.
     */
    @Test
    void namesSpendWhoseCategoryHasSinceBeenDeleted() {
        when(categoryRepository.findByUserId("u1")).thenReturn(List.of());
        when(transactionRepository.sumExpenseByCategoryInWindowGrouped(anyString(), anyLong(), anyLong()))
                .thenReturn(List.of(new CategoryTotal("c-gone", 5_000L)));

        SpendingSnapshot snapshot = service.snapshotFor(user("USD", "UTC"));

        assertEquals("Uncategorised", snapshot.getMonths().get(0).getCategories().get(0).getName());
        assertEquals(5_000L, snapshot.getMonths().get(0).getCategories().get(0).getAmount());
    }

    @Test
    void capsTheBreakdownSoThePromptStaysABriefingNotALedgerDump() {
        when(categoryRepository.findByUserId("u1")).thenReturn(List.of());
        when(transactionRepository.sumExpenseByCategoryInWindowGrouped(anyString(), anyLong(), anyLong()))
                .thenReturn(java.util.stream.IntStream.range(0, 20)
                        .mapToObj(i -> new CategoryTotal("c" + i, 1_000L - i))
                        .toList());

        assertEquals(8, service.snapshotFor(user("USD", "UTC")).getMonths().get(0).getCategories().size());
    }

    // --- the empty case, which is answered without a model call at all ---

    @Test
    void reportsEmptyForAUserWithNothingRecorded() {
        when(categoryRepository.findByUserId("u1")).thenReturn(List.of());

        assertTrue(service.snapshotFor(user("USD", "UTC")).isEmpty());
    }

    @Test
    void isNotEmptyOnceThereIsIncomeEvenWithNoSpendYet() {
        when(categoryRepository.findByUserId("u1")).thenReturn(List.of());
        when(transactionRepository.sumAmountByTypeInWindow(anyString(), eq(TransactionType.INCOME), anyLong(), anyLong()))
                .thenReturn(500_000L);

        assertFalse(service.snapshotFor(user("USD", "UTC")).isEmpty());
    }

    /** Nothing about a transaction's note, payee, or timing leaves for the model (§9). */
    @Test
    void neverLoadsIndividualTransactions() {
        when(categoryRepository.findByUserId("u1")).thenReturn(List.of());

        service.snapshotFor(user("USD", "UTC"));

        verify(transactionRepository, org.mockito.Mockito.never()).findByUserId(any());
    }

    // --- fixtures ---

    private static User user(String currency, String timezone) {
        User u = new User();
        u.setId("u1");
        u.setActiveCurrency(currency);
        u.setTimezone(timezone);
        return u;
    }

    private static Category category(String id, String name) {
        Category c = new Category();
        c.setId(id);
        c.setName(name);
        c.setKind(CategoryKind.EXPENSE);
        return c;
    }
}
