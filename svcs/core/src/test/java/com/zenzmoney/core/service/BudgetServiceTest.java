package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.AccountStatus;
import com.zenzmoney.common.domain.BudgetStatus;
import com.zenzmoney.common.domain.BudgetPeriod;
import com.zenzmoney.common.domain.CategoryKind;
import com.zenzmoney.common.domain.TimeUtils;
import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.common.exception.NotFoundException;
import com.zenzmoney.core.entity.Account;
import com.zenzmoney.core.entity.Budget;
import com.zenzmoney.core.entity.Category;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.AccountRepository;
import com.zenzmoney.core.repository.BudgetRepository;
import com.zenzmoney.core.repository.CategoryRepository;
import com.zenzmoney.core.repository.TransactionRepository;
import com.zenzmoney.core.web.dto.BudgetResponse;
import com.zenzmoney.core.web.dto.CreateBudgetRequest;
import com.zenzmoney.core.web.dto.UpdateBudgetRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BudgetServiceTest {

    @Mock BudgetRepository budgetRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock AccountRepository accountRepository;
    @Mock CurrentUserService currentUser;
    @InjectMocks BudgetService budgetService;

    private User user(String id) {
        User u = new User();
        u.setId(id);
        return u;
    }

    private Account account(String id, String userId, String currency) {
        Account a = new Account();
        a.setId(id);
        a.setUserId(userId);
        a.setCurrency(currency);
        a.setStatus(AccountStatus.ACTIVE);
        return a;
    }

    private Category category(String id, String userId, CategoryKind kind) {
        Category c = new Category();
        c.setId(id);
        c.setUserId(userId);
        c.setName("Food & Drinks");
        c.setKind(kind);
        return c;
    }

    private Budget owned(String id, String userId, String accountId, String categoryId) {
        Budget b = new Budget();
        b.setId(id);
        b.setUserId(userId);
        b.setAccountId(accountId);
        b.setCategoryId(categoryId);
        b.setPeriod(BudgetPeriod.MONTHLY);
        b.setAmountLimit(50_000);
        b.setStatus(BudgetStatus.ACTIVE);
        return b;
    }

    private CreateBudgetRequest createReq(String accountId, String categoryId, BudgetPeriod period, long limit) {
        CreateBudgetRequest r = new CreateBudgetRequest();
        r.setAccountId(accountId);
        r.setCategoryId(categoryId);
        r.setPeriod(period);
        r.setAmountLimit(limit);
        return r;
    }

    @Test
    void create_categoryBudget_derivesCurrencyFromAccount_andComputesSpentForCategory() {
        when(currentUser.requireUser()).thenReturn(user("u1"));
        when(accountRepository.findByIdAndUserId("acc1", "u1")).thenReturn(Optional.of(account("acc1", "u1", "USD")));
        when(accountRepository.findById("acc1")).thenReturn(Optional.of(account("acc1", "u1", "USD")));
        when(categoryRepository.findByIdAndUserId("c1", "u1"))
                .thenReturn(Optional.of(category("c1", "u1", CategoryKind.EXPENSE)));
        when(budgetRepository.findByUserId("u1")).thenReturn(List.of());
        when(budgetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.sumExpenseByCategoryInWindow(eq("u1"), eq("c1"), anyLong(), anyLong()))
                .thenReturn(12_000L);

        BudgetResponse resp = budgetService.create(createReq("acc1", "c1", BudgetPeriod.MONTHLY, 50_000));

        ArgumentCaptor<Budget> saved = ArgumentCaptor.forClass(Budget.class);
        verify(budgetRepository).save(saved.capture());
        assertEquals("u1", saved.getValue().getUserId());
        assertEquals("acc1", saved.getValue().getAccountId());
        assertEquals(BudgetStatus.ACTIVE, saved.getValue().getStatus());
        assertEquals("USD", resp.getCurrency());
        assertEquals(12_000L, resp.getSpent());
        assertEquals(38_000L, resp.getRemaining());   // 50_000 - 12_000
    }

    @Test
    void create_overallBudget_hasNullCategory_andSumsAllExpense() {
        when(currentUser.requireUser()).thenReturn(user("u1"));
        when(accountRepository.findByIdAndUserId("acc1", "u1")).thenReturn(Optional.of(account("acc1", "u1", "USD")));
        when(accountRepository.findById("acc1")).thenReturn(Optional.of(account("acc1", "u1", "USD")));
        when(budgetRepository.findByUserId("u1")).thenReturn(List.of());
        when(budgetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.sumExpenseInWindow(eq("u1"), anyLong(), anyLong())).thenReturn(9_000L);

        BudgetResponse resp = budgetService.create(createReq("acc1", null, BudgetPeriod.MONTHLY, 30_000));

        assertNull(resp.getCategoryId());
        assertEquals(9_000L, resp.getSpent());
        verify(categoryRepository, never()).findByIdAndUserId(any(), any());
    }

    @Test
    void create_rejects_whenAccountNotFound() {
        when(currentUser.requireUser()).thenReturn(user("u1"));
        when(accountRepository.findByIdAndUserId("acc1", "u1")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class,
                () -> budgetService.create(createReq("acc1", null, BudgetPeriod.MONTHLY, 50_000)));
        verify(budgetRepository, never()).save(any());
    }

    @Test
    void create_rejects_whenAccountIsDeleted() {
        Account deleted = account("acc1", "u1", "USD");
        deleted.setStatus(AccountStatus.DELETED);
        when(currentUser.requireUser()).thenReturn(user("u1"));
        when(accountRepository.findByIdAndUserId("acc1", "u1")).thenReturn(Optional.of(deleted));

        assertThrows(BadRequestException.class,
                () -> budgetService.create(createReq("acc1", null, BudgetPeriod.MONTHLY, 50_000)));
        verify(budgetRepository, never()).save(any());
    }

    @Test
    void create_rejects_whenCategoryIsIncome() {
        when(currentUser.requireUser()).thenReturn(user("u1"));
        when(accountRepository.findByIdAndUserId("acc1", "u1")).thenReturn(Optional.of(account("acc1", "u1", "USD")));
        when(categoryRepository.findByIdAndUserId("c1", "u1"))
                .thenReturn(Optional.of(category("c1", "u1", CategoryKind.INCOME)));

        assertThrows(BadRequestException.class,
                () -> budgetService.create(createReq("acc1", "c1", BudgetPeriod.MONTHLY, 50_000)));
        verify(budgetRepository, never()).save(any());
    }

    @Test
    void create_rejects_whenCategoryNotFound() {
        when(currentUser.requireUser()).thenReturn(user("u1"));
        when(accountRepository.findByIdAndUserId("acc1", "u1")).thenReturn(Optional.of(account("acc1", "u1", "USD")));
        when(categoryRepository.findByIdAndUserId("c1", "u1")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class,
                () -> budgetService.create(createReq("acc1", "c1", BudgetPeriod.MONTHLY, 50_000)));
        verify(budgetRepository, never()).save(any());
    }

    @Test
    void create_rejects_duplicateActiveForSameAccountCategoryAndPeriod() {
        when(currentUser.requireUser()).thenReturn(user("u1"));
        when(accountRepository.findByIdAndUserId("acc1", "u1")).thenReturn(Optional.of(account("acc1", "u1", "USD")));
        when(categoryRepository.findByIdAndUserId("c1", "u1"))
                .thenReturn(Optional.of(category("c1", "u1", CategoryKind.EXPENSE)));
        when(budgetRepository.findByUserId("u1")).thenReturn(List.of(owned("b1", "u1", "acc1", "c1")));

        assertThrows(BadRequestException.class,
                () -> budgetService.create(createReq("acc1", "c1", BudgetPeriod.MONTHLY, 50_000)));
        verify(budgetRepository, never()).save(any());
    }

    @Test
    void create_allowsSecondBudget_whenAccountDiffers() {
        when(currentUser.requireUser()).thenReturn(user("u1"));
        when(accountRepository.findByIdAndUserId("acc2", "u1")).thenReturn(Optional.of(account("acc2", "u1", "USD")));
        when(accountRepository.findById("acc2")).thenReturn(Optional.of(account("acc2", "u1", "USD")));
        when(categoryRepository.findByIdAndUserId("c1", "u1"))
                .thenReturn(Optional.of(category("c1", "u1", CategoryKind.EXPENSE)));
        when(budgetRepository.findByUserId("u1")).thenReturn(List.of(owned("b1", "u1", "acc1", "c1"))); // different account
        when(budgetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.sumExpenseByCategoryInWindow(eq("u1"), eq("c1"), anyLong(), anyLong()))
                .thenReturn(0L);

        BudgetResponse resp = budgetService.create(createReq("acc2", "c1", BudgetPeriod.MONTHLY, 10_000));

        assertEquals("acc2", resp.getAccountId());
    }

    @Test
    void create_allowsSecondBudget_whenPeriodDiffers() {
        when(currentUser.requireUser()).thenReturn(user("u1"));
        when(accountRepository.findByIdAndUserId("acc1", "u1")).thenReturn(Optional.of(account("acc1", "u1", "USD")));
        when(accountRepository.findById("acc1")).thenReturn(Optional.of(account("acc1", "u1", "USD")));
        when(categoryRepository.findByIdAndUserId("c1", "u1"))
                .thenReturn(Optional.of(category("c1", "u1", CategoryKind.EXPENSE)));
        when(budgetRepository.findByUserId("u1")).thenReturn(List.of(owned("b1", "u1", "acc1", "c1"))); // MONTHLY
        when(budgetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.sumExpenseByCategoryInWindow(eq("u1"), eq("c1"), anyLong(), anyLong()))
                .thenReturn(0L);

        BudgetResponse resp = budgetService.create(createReq("acc1", "c1", BudgetPeriod.YEARLY, 10_000));

        assertEquals(BudgetPeriod.YEARLY, resp.getPeriod());
    }

    @Test
    void list_returnsMappedBudgets_excludingArchivedByDefault() {
        Budget active = owned("b1", "u1", "acc1", "c1");
        Budget archived = owned("b2", "u1", "acc1", "c1");
        archived.setStatus(BudgetStatus.ARCHIVED);
        when(currentUser.requireUser()).thenReturn(user("u1"));
        when(budgetRepository.findByUserId("u1")).thenReturn(List.of(active, archived));
        when(accountRepository.findById("acc1")).thenReturn(Optional.of(account("acc1", "u1", "USD")));
        when(transactionRepository.sumExpenseByCategoryInWindow(eq("u1"), eq("c1"), anyLong(), anyLong()))
                .thenReturn(0L);

        List<BudgetResponse> resp = budgetService.list(false);

        assertEquals(1, resp.size());
        assertEquals("b1", resp.get(0).getId());
    }

    @Test
    void update_changesLimitAndRollover() {
        Budget b = owned("b1", "u1", "acc1", "c1");
        when(currentUser.requireUser()).thenReturn(user("u1"));
        when(budgetRepository.findByIdAndUserId("b1", "u1")).thenReturn(Optional.of(b));
        when(budgetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountRepository.findById("acc1")).thenReturn(Optional.of(account("acc1", "u1", "USD")));
        when(transactionRepository.sumExpenseByCategoryInWindow(eq("u1"), eq("c1"), anyLong(), anyLong()))
                .thenReturn(0L);

        UpdateBudgetRequest req = new UpdateBudgetRequest();
        req.setAmountLimit(80_000L);
        req.setRollover(true);
        BudgetResponse resp = budgetService.update("b1", req);

        assertEquals(80_000L, resp.getAmountLimit());
        assertTrue(resp.isRollover());
    }

    @Test
    void archive_setsStatusArchived() {
        Budget b = owned("b1", "u1", "acc1", "c1");
        when(currentUser.requireUser()).thenReturn(user("u1"));
        when(budgetRepository.findByIdAndUserId("b1", "u1")).thenReturn(Optional.of(b));
        when(budgetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountRepository.findById("acc1")).thenReturn(Optional.of(account("acc1", "u1", "USD")));
        when(transactionRepository.sumExpenseByCategoryInWindow(eq("u1"), eq("c1"), anyLong(), anyLong()))
                .thenReturn(0L);

        BudgetResponse resp = budgetService.archive("b1");

        assertEquals(BudgetStatus.ARCHIVED, resp.getStatus());
    }

    @Test
    void delete_hardDeletes() {
        Budget b = owned("b1", "u1", "acc1", "c1");
        when(currentUser.requireUserId()).thenReturn("u1");
        when(budgetRepository.findByIdAndUserId("b1", "u1")).thenReturn(Optional.of(b));

        budgetService.delete("b1");

        verify(budgetRepository).delete(b);
    }

    @Test
    void get_notOwned_throwsNotFound() {
        when(currentUser.requireUser()).thenReturn(user("u1"));
        when(budgetRepository.findByIdAndUserId("x", "u1")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> budgetService.get("x"));
    }

    // --- currentWindow: calendar-aligned, per-user-zone period boundaries ---

    @Test
    void currentWindow_monthly_returnsTheCalendarMonthContainingNow() {
        long now = Instant.parse("2026-08-15T12:00:00Z").toEpochMilli();

        long[] w = BudgetService.currentWindow(BudgetPeriod.MONTHLY, ZoneOffset.UTC, now);

        assertEquals(TimeUtils.startOfMonth(YearMonth.of(2026, 8), ZoneOffset.UTC), w[0]);
        assertEquals(TimeUtils.startOfMonth(YearMonth.of(2026, 9), ZoneOffset.UTC), w[1]);
    }

    @Test
    void currentWindow_yearly_returnsTheCalendarYearContainingNow() {
        long now = Instant.parse("2026-06-15T00:00:00Z").toEpochMilli();

        long[] w = BudgetService.currentWindow(BudgetPeriod.YEARLY, ZoneOffset.UTC, now);

        assertEquals(TimeUtils.startOfYear(Year.of(2026), ZoneOffset.UTC), w[0]);
        assertEquals(TimeUtils.startOfYear(Year.of(2027), ZoneOffset.UTC), w[1]);
    }

    /**
     * 2026-03-31T20:00:00Z is still March in UTC but already 2026-04-01T01:30 in
     * Colombo (UTC+5:30) — the same instant must resolve to different calendar
     * months depending on whose zone it's read in.
     */
    @Test
    void currentWindow_monthly_usesTheGivenZone_notAFixedOne() {
        long now = Instant.parse("2026-03-31T20:00:00Z").toEpochMilli();
        ZoneId colombo = ZoneId.of("Asia/Colombo");

        long[] utcWindow = BudgetService.currentWindow(BudgetPeriod.MONTHLY, ZoneOffset.UTC, now);
        long[] colomboWindow = BudgetService.currentWindow(BudgetPeriod.MONTHLY, colombo, now);

        assertEquals(TimeUtils.startOfMonth(YearMonth.of(2026, 3), ZoneOffset.UTC), utcWindow[0]);
        assertEquals(TimeUtils.startOfMonth(YearMonth.of(2026, 4), colombo), colomboWindow[0]);
    }
}
