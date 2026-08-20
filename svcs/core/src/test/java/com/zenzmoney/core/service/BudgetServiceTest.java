package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.AccountStatus;
import com.zenzmoney.common.domain.BudgetStatus;
import com.zenzmoney.common.domain.BudgetPeriod;
import com.zenzmoney.common.domain.CategoryKind;
import com.zenzmoney.common.domain.TimeUtils;
import com.zenzmoney.common.domain.TransactionType;
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
import com.zenzmoney.core.web.dto.BudgetSummaryResponse;
import com.zenzmoney.core.web.dto.CreateBudgetRequest;
import com.zenzmoney.core.web.dto.UpdateBudgetRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
import static org.mockito.ArgumentMatchers.isNull;
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

    private static final String JULY = "2026-07";
    private static final String AUGUST = "2026-08";

    private User user(String id) {
        User u = new User();
        u.setId(id);
        u.setActiveCurrency("USD");
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

    private Budget owned(String id, String userId, String accountId, String categoryId, String periodKey) {
        Budget b = new Budget();
        b.setId(id);
        b.setUserId(userId);
        b.setAccountId(accountId);
        b.setCategoryId(categoryId);
        b.setPeriod(BudgetPeriod.MONTHLY);
        b.setPeriodKey(periodKey);
        b.setAmountLimit(50_000);
        b.setStatus(BudgetStatus.ACTIVE);
        return b;
    }

    private CreateBudgetRequest createReq(String accountId, String categoryId,
                                          BudgetPeriod period, String periodKey, long limit) {
        CreateBudgetRequest r = new CreateBudgetRequest();
        r.setAccountId(accountId);
        r.setCategoryId(categoryId);
        r.setPeriod(period);
        r.setPeriodKey(periodKey);
        r.setAmountLimit(limit);
        return r;
    }

    private void noActiveDuplicate(String accountId, BudgetPeriod period, String periodKey) {
        when(budgetRepository.findByUserIdAndAccountIdAndPeriodAndPeriodKeyAndStatus(
                "u1", accountId, period, periodKey, BudgetStatus.ACTIVE)).thenReturn(List.of());
    }

    @Test
    void create_categoryBudget_derivesCurrencyFromAccount_andComputesSpentForCategory() {
        when(currentUser.requireUser()).thenReturn(user("u1"));
        when(accountRepository.findByIdAndUserId("acc1", "u1")).thenReturn(Optional.of(account("acc1", "u1", "USD")));
        when(categoryRepository.findByIdAndUserId("c1", "u1"))
                .thenReturn(Optional.of(category("c1", "u1", CategoryKind.EXPENSE)));
        noActiveDuplicate("acc1", BudgetPeriod.MONTHLY, AUGUST);
        when(budgetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.sumExpenseByCategoryInWindow(eq("u1"), eq("c1"), anyLong(), anyLong(), eq("acc1")))
                .thenReturn(12_000L);

        BudgetResponse resp = budgetService.create(
                createReq("acc1", "c1", BudgetPeriod.MONTHLY, AUGUST, 50_000));

        ArgumentCaptor<Budget> saved = ArgumentCaptor.forClass(Budget.class);
        verify(budgetRepository).save(saved.capture());
        assertEquals("u1", saved.getValue().getUserId());
        assertEquals("acc1", saved.getValue().getAccountId());
        assertEquals(AUGUST, saved.getValue().getPeriodKey());
        assertEquals(BudgetStatus.ACTIVE, saved.getValue().getStatus());
        assertEquals("USD", resp.getCurrency());
        assertEquals(AUGUST, resp.getPeriodKey());
        assertEquals(12_000L, resp.getSpent());
        assertEquals(38_000L, resp.getRemaining());   // 50_000 - 12_000
    }

    /**
     * The whole point of linking a budget to an account (§1.7): spend on another
     * account must not count against this budget.
     */
    @Test
    void create_scopesSpentToTheBudgetsOwnAccount() {
        when(currentUser.requireUser()).thenReturn(user("u1"));
        when(accountRepository.findByIdAndUserId("acc2", "u1")).thenReturn(Optional.of(account("acc2", "u1", "USD")));
        when(categoryRepository.findByIdAndUserId("c1", "u1"))
                .thenReturn(Optional.of(category("c1", "u1", CategoryKind.EXPENSE)));
        noActiveDuplicate("acc2", BudgetPeriod.MONTHLY, AUGUST);
        when(budgetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.sumExpenseByCategoryInWindow(eq("u1"), eq("c1"), anyLong(), anyLong(), eq("acc2")))
                .thenReturn(0L);

        budgetService.create(createReq("acc2", "c1", BudgetPeriod.MONTHLY, AUGUST, 50_000));

        verify(transactionRepository)
                .sumExpenseByCategoryInWindow(eq("u1"), eq("c1"), anyLong(), anyLong(), eq("acc2"));
    }

    @Test
    void create_overallBudget_hasNullCategory_andSumsAllExpenseOnThatAccount() {
        when(currentUser.requireUser()).thenReturn(user("u1"));
        when(accountRepository.findByIdAndUserId("acc1", "u1")).thenReturn(Optional.of(account("acc1", "u1", "USD")));
        noActiveDuplicate("acc1", BudgetPeriod.MONTHLY, AUGUST);
        when(budgetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.sumExpenseInWindow(eq("u1"), anyLong(), anyLong(), eq("acc1"))).thenReturn(9_000L);

        BudgetResponse resp = budgetService.create(
                createReq("acc1", null, BudgetPeriod.MONTHLY, AUGUST, 30_000));

        assertNull(resp.getCategoryId());
        assertEquals(9_000L, resp.getSpent());
        verify(categoryRepository, never()).findByIdAndUserId(any(), any());
    }

    @Test
    void create_rejects_whenAccountNotFound() {
        when(currentUser.requireUser()).thenReturn(user("u1"));
        when(accountRepository.findByIdAndUserId("acc1", "u1")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> budgetService.create(
                createReq("acc1", null, BudgetPeriod.MONTHLY, AUGUST, 50_000)));
        verify(budgetRepository, never()).save(any());
    }

    @Test
    void create_rejects_whenAccountIsDeleted() {
        Account deleted = account("acc1", "u1", "USD");
        deleted.setStatus(AccountStatus.DELETED);
        when(currentUser.requireUser()).thenReturn(user("u1"));
        when(accountRepository.findByIdAndUserId("acc1", "u1")).thenReturn(Optional.of(deleted));

        assertThrows(BadRequestException.class, () -> budgetService.create(
                createReq("acc1", null, BudgetPeriod.MONTHLY, AUGUST, 50_000)));
        verify(budgetRepository, never()).save(any());
    }

    @Test
    void create_rejects_whenCategoryIsIncome() {
        when(currentUser.requireUser()).thenReturn(user("u1"));
        when(accountRepository.findByIdAndUserId("acc1", "u1")).thenReturn(Optional.of(account("acc1", "u1", "USD")));
        when(categoryRepository.findByIdAndUserId("c1", "u1"))
                .thenReturn(Optional.of(category("c1", "u1", CategoryKind.INCOME)));

        assertThrows(BadRequestException.class, () -> budgetService.create(
                createReq("acc1", "c1", BudgetPeriod.MONTHLY, AUGUST, 50_000)));
        verify(budgetRepository, never()).save(any());
    }

    @Test
    void create_rejects_whenCategoryNotFound() {
        when(currentUser.requireUser()).thenReturn(user("u1"));
        when(accountRepository.findByIdAndUserId("acc1", "u1")).thenReturn(Optional.of(account("acc1", "u1", "USD")));
        when(categoryRepository.findByIdAndUserId("c1", "u1")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> budgetService.create(
                createReq("acc1", "c1", BudgetPeriod.MONTHLY, AUGUST, 50_000)));
        verify(budgetRepository, never()).save(any());
    }

    // --- periodKey: the month or year a budget applies to ---

    @Test
    void create_rejects_monthlyBudgetWithAYearOnlyPeriodKey() {
        when(currentUser.requireUser()).thenReturn(user("u1"));
        when(accountRepository.findByIdAndUserId("acc1", "u1")).thenReturn(Optional.of(account("acc1", "u1", "USD")));

        assertThrows(BadRequestException.class, () -> budgetService.create(
                createReq("acc1", null, BudgetPeriod.MONTHLY, "2026", 50_000)));
        verify(budgetRepository, never()).save(any());
    }

    @Test
    void create_rejects_yearlyBudgetWithAMonthPeriodKey() {
        when(currentUser.requireUser()).thenReturn(user("u1"));
        when(accountRepository.findByIdAndUserId("acc1", "u1")).thenReturn(Optional.of(account("acc1", "u1", "USD")));

        assertThrows(BadRequestException.class, () -> budgetService.create(
                createReq("acc1", null, BudgetPeriod.YEARLY, AUGUST, 50_000)));
        verify(budgetRepository, never()).save(any());
    }

    @Test
    void create_rejects_garbagePeriodKey() {
        when(currentUser.requireUser()).thenReturn(user("u1"));
        when(accountRepository.findByIdAndUserId("acc1", "u1")).thenReturn(Optional.of(account("acc1", "u1", "USD")));

        assertThrows(BadRequestException.class, () -> budgetService.create(
                createReq("acc1", null, BudgetPeriod.MONTHLY, "august", 50_000)));
    }

    @Test
    void create_rejects_duplicateActiveForSameAccountCategoryPeriodAndPeriodKey() {
        when(currentUser.requireUser()).thenReturn(user("u1"));
        when(accountRepository.findByIdAndUserId("acc1", "u1")).thenReturn(Optional.of(account("acc1", "u1", "USD")));
        when(categoryRepository.findByIdAndUserId("c1", "u1"))
                .thenReturn(Optional.of(category("c1", "u1", CategoryKind.EXPENSE)));
        when(budgetRepository.findByUserIdAndAccountIdAndPeriodAndPeriodKeyAndStatus(
                "u1", "acc1", BudgetPeriod.MONTHLY, AUGUST, BudgetStatus.ACTIVE))
                .thenReturn(List.of(owned("b1", "u1", "acc1", "c1", AUGUST)));

        assertThrows(BadRequestException.class, () -> budgetService.create(
                createReq("acc1", "c1", BudgetPeriod.MONTHLY, AUGUST, 50_000)));
        verify(budgetRepository, never()).save(any());
    }

    /** Food at 2000 in July and 3000 in August — different months, different rows. */
    @Test
    void create_allowsSameCategory_inADifferentMonth() {
        when(currentUser.requireUser()).thenReturn(user("u1"));
        when(accountRepository.findByIdAndUserId("acc1", "u1")).thenReturn(Optional.of(account("acc1", "u1", "USD")));
        when(categoryRepository.findByIdAndUserId("c1", "u1"))
                .thenReturn(Optional.of(category("c1", "u1", CategoryKind.EXPENSE)));
        noActiveDuplicate("acc1", BudgetPeriod.MONTHLY, AUGUST);   // July's row is in a different slot
        when(budgetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.sumExpenseByCategoryInWindow(eq("u1"), eq("c1"), anyLong(), anyLong(), eq("acc1")))
                .thenReturn(0L);

        BudgetResponse resp = budgetService.create(
                createReq("acc1", "c1", BudgetPeriod.MONTHLY, AUGUST, 300_000));

        assertEquals(AUGUST, resp.getPeriodKey());
        assertEquals(300_000L, resp.getAmountLimit());
    }

    @Test
    void list_returnsMappedBudgets_excludingArchivedByDefault() {
        Budget active = owned("b1", "u1", "acc1", "c1", AUGUST);
        Budget archived = owned("b2", "u1", "acc1", "c1", JULY);
        archived.setStatus(BudgetStatus.ARCHIVED);
        when(currentUser.requireUser()).thenReturn(user("u1"));
        when(budgetRepository.findByUserId("u1")).thenReturn(List.of(active, archived));
        when(accountRepository.findByUserId("u1")).thenReturn(List.of(account("acc1", "u1", "USD")));
        when(transactionRepository.sumExpenseByCategoryInWindow(eq("u1"), eq("c1"), anyLong(), anyLong(), eq("acc1")))
                .thenReturn(0L);

        List<BudgetResponse> resp = budgetService.list(false);

        assertEquals(1, resp.size());
        assertEquals("b1", resp.get(0).getId());
    }

    @Test
    void update_changesLimitAndRollover() {
        Budget b = owned("b1", "u1", "acc1", "c1", AUGUST);
        when(currentUser.requireUser()).thenReturn(user("u1"));
        when(budgetRepository.findByIdAndUserId("b1", "u1")).thenReturn(Optional.of(b));
        when(budgetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountRepository.findByIdAndUserId("acc1", "u1")).thenReturn(Optional.of(account("acc1", "u1", "USD")));
        when(transactionRepository.sumExpenseByCategoryInWindow(eq("u1"), eq("c1"), anyLong(), anyLong(), eq("acc1")))
                .thenReturn(0L);

        UpdateBudgetRequest req = new UpdateBudgetRequest();
        req.setAmountLimit(80_000L);
        req.setRollover(true);
        BudgetResponse resp = budgetService.update("b1", req);

        assertEquals(80_000L, resp.getAmountLimit());
        assertEquals(AUGUST, resp.getPeriodKey());   // identity is untouched by an edit
        assertTrue(resp.isRollover());
    }

    @Test
    void archive_setsStatusArchived() {
        Budget b = owned("b1", "u1", "acc1", "c1", AUGUST);
        when(currentUser.requireUser()).thenReturn(user("u1"));
        when(budgetRepository.findByIdAndUserId("b1", "u1")).thenReturn(Optional.of(b));
        when(budgetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountRepository.findByIdAndUserId("acc1", "u1")).thenReturn(Optional.of(account("acc1", "u1", "USD")));
        when(transactionRepository.sumExpenseByCategoryInWindow(eq("u1"), eq("c1"), anyLong(), anyLong(), eq("acc1")))
                .thenReturn(0L);

        BudgetResponse resp = budgetService.archive("b1");

        assertEquals(BudgetStatus.ARCHIVED, resp.getStatus());
    }

    @Test
    void delete_isSoft_setsStatusDeletedAndKeepsTheRow() {
        Budget b = owned("b1", "u1", "acc1", "c1", AUGUST);
        when(currentUser.requireUserId()).thenReturn("u1");
        when(budgetRepository.findByIdAndUserId("b1", "u1")).thenReturn(Optional.of(b));

        budgetService.delete("b1");

        assertEquals(BudgetStatus.DELETED, b.getStatus());
        verify(budgetRepository).save(b);
        verify(budgetRepository, never()).delete(any());
    }

    @Test
    void delete_rejects_whenAlreadyDeleted() {
        Budget b = owned("b1", "u1", "acc1", "c1", AUGUST);
        b.setStatus(BudgetStatus.DELETED);
        when(currentUser.requireUserId()).thenReturn("u1");
        when(budgetRepository.findByIdAndUserId("b1", "u1")).thenReturn(Optional.of(b));

        assertThrows(BadRequestException.class, () -> budgetService.delete("b1"));
        verify(budgetRepository, never()).save(any());
    }

    /** A deleted budget is history, not a plan — no listing surfaces it. */
    @Test
    void list_neverReturnsDeleted_evenWithIncludeArchived() {
        Budget deleted = owned("b1", "u1", "acc1", "c1", AUGUST);
        deleted.setStatus(BudgetStatus.DELETED);
        Budget archived = owned("b2", "u1", "acc1", "c1", JULY);
        archived.setStatus(BudgetStatus.ARCHIVED);
        when(currentUser.requireUser()).thenReturn(user("u1"));
        when(budgetRepository.findByUserId("u1")).thenReturn(List.of(deleted, archived));
        when(accountRepository.findByUserId("u1")).thenReturn(List.of(account("acc1", "u1", "USD")));
        when(transactionRepository.sumExpenseByCategoryInWindow(eq("u1"), eq("c1"), anyLong(), anyLong(), eq("acc1")))
                .thenReturn(0L);

        List<BudgetResponse> resp = budgetService.list(true);

        assertEquals(1, resp.size());
        assertEquals("b2", resp.get(0).getId());
    }

    @Test
    void update_rejects_whenBudgetIsDeleted() {
        Budget b = owned("b1", "u1", "acc1", "c1", AUGUST);
        b.setStatus(BudgetStatus.DELETED);
        when(currentUser.requireUser()).thenReturn(user("u1"));
        when(budgetRepository.findByIdAndUserId("b1", "u1")).thenReturn(Optional.of(b));

        UpdateBudgetRequest req = new UpdateBudgetRequest();
        req.setAmountLimit(80_000L);
        assertThrows(BadRequestException.class, () -> budgetService.update("b1", req));
        verify(budgetRepository, never()).save(any());
    }

    @Test
    void archive_rejects_whenBudgetIsDeleted() {
        Budget b = owned("b1", "u1", "acc1", "c1", AUGUST);
        b.setStatus(BudgetStatus.DELETED);
        when(currentUser.requireUser()).thenReturn(user("u1"));
        when(budgetRepository.findByIdAndUserId("b1", "u1")).thenReturn(Optional.of(b));

        assertThrows(BadRequestException.class, () -> budgetService.archive("b1"));
        verify(budgetRepository, never()).save(any());
    }

    @Test
    void get_notOwned_throwsNotFound() {
        when(currentUser.requireUser()).thenReturn(user("u1"));
        when(budgetRepository.findByIdAndUserId("x", "u1")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> budgetService.get("x"));
    }

    // --- monthly summary: the caps set for a month against what was spent ---

    @Test
    void summary_totalsCoverCategoryBudgetsOnly_soOverallSpendIsNotDoubleCounted() {
        Budget food = owned("b1", "u1", "acc1", "c1", AUGUST);
        food.setAmountLimit(200_000);
        Budget overall = owned("b2", "u1", "acc1", null, AUGUST);
        overall.setAmountLimit(500_000);
        when(currentUser.requireUser()).thenReturn(user("u1"));
        when(accountRepository.findByUserId("u1")).thenReturn(List.of(account("acc1", "u1", "USD")));
        when(budgetRepository.findByUserIdAndPeriodAndPeriodKeyAndStatus(
                "u1", BudgetPeriod.MONTHLY, AUGUST, BudgetStatus.ACTIVE)).thenReturn(List.of(food, overall));
        when(transactionRepository.sumExpenseByCategoryInWindow(eq("u1"), eq("c1"), anyLong(), anyLong(), eq("acc1")))
                .thenReturn(150_000L);
        when(transactionRepository.sumExpenseInWindow(eq("u1"), anyLong(), anyLong(), eq("acc1")))
                .thenReturn(300_000L);
        when(transactionRepository.sumAmountByTypeInWindow(
                eq("u1"), eq(TransactionType.EXPENSE), anyLong(), anyLong(), isNull())).thenReturn(320_000L);

        BudgetSummaryResponse resp = budgetService.summary(AUGUST);

        assertEquals(AUGUST, resp.getMonth());
        assertEquals(200_000L, resp.getTotalLimit());      // the overall cap is listed, not summed
        assertEquals(150_000L, resp.getTotalSpent());
        assertEquals(50_000L, resp.getTotalRemaining());
        assertEquals(320_000L, resp.getMonthExpenses());
        assertEquals("USD", resp.getCurrency());
        assertEquals(2, resp.getBudgets().size());
    }

    @Test
    void summary_windowIsTheRequestedMonth_notTheCurrentOne() {
        when(currentUser.requireUser()).thenReturn(user("u1"));
        when(accountRepository.findByUserId("u1")).thenReturn(List.of());
        when(budgetRepository.findByUserIdAndPeriodAndPeriodKeyAndStatus(
                "u1", BudgetPeriod.MONTHLY, JULY, BudgetStatus.ACTIVE)).thenReturn(List.of());
        when(transactionRepository.sumAmountByTypeInWindow(
                eq("u1"), eq(TransactionType.EXPENSE), anyLong(), anyLong(), isNull())).thenReturn(0L);

        BudgetSummaryResponse resp = budgetService.summary(JULY);

        assertEquals(TimeUtils.startOfMonth(YearMonth.of(2026, 7), ZoneOffset.UTC), resp.getFrom());
        assertEquals(TimeUtils.startOfMonth(YearMonth.of(2026, 8), ZoneOffset.UTC), resp.getTo());
        assertEquals(0L, resp.getTotalLimit());
    }

    @Test
    void summary_defaultsToTheCallersCurrentMonth() {
        String thisMonth = TimeUtils.monthOf(TimeUtils.now(), ZoneOffset.UTC).toString();
        when(currentUser.requireUser()).thenReturn(user("u1"));
        when(accountRepository.findByUserId("u1")).thenReturn(List.of());
        when(budgetRepository.findByUserIdAndPeriodAndPeriodKeyAndStatus(
                "u1", BudgetPeriod.MONTHLY, thisMonth, BudgetStatus.ACTIVE)).thenReturn(List.of());
        when(transactionRepository.sumAmountByTypeInWindow(
                eq("u1"), eq(TransactionType.EXPENSE), anyLong(), anyLong(), isNull())).thenReturn(0L);

        assertEquals(thisMonth, budgetService.summary(null).getMonth());
    }

    @Test
    void summary_rejects_malformedMonth() {
        when(currentUser.requireUser()).thenReturn(user("u1"));

        assertThrows(BadRequestException.class, () -> budgetService.summary("2026-8"));
    }

    // --- windowFor: the period the budget itself names, never "now" ---

    @Test
    void windowFor_monthly_returnsTheMonthTheKeyNames() {
        long[] w = BudgetService.windowFor(BudgetPeriod.MONTHLY, JULY, ZoneOffset.UTC);

        assertEquals(TimeUtils.startOfMonth(YearMonth.of(2026, 7), ZoneOffset.UTC), w[0]);
        assertEquals(TimeUtils.startOfMonth(YearMonth.of(2026, 8), ZoneOffset.UTC), w[1]);
    }

    @Test
    void windowFor_yearly_returnsTheYearTheKeyNames() {
        long[] w = BudgetService.windowFor(BudgetPeriod.YEARLY, "2026", ZoneOffset.UTC);

        assertEquals(TimeUtils.startOfYear(Year.of(2026), ZoneOffset.UTC), w[0]);
        assertEquals(TimeUtils.startOfYear(Year.of(2027), ZoneOffset.UTC), w[1]);
    }

    /**
     * The same August budget starts at a different instant for a user in Colombo
     * (UTC+5:30) than for one in UTC — the boundary is the owner's midnight.
     */
    @Test
    void windowFor_usesTheGivenZone_notAFixedOne() {
        ZoneId colombo = ZoneId.of("Asia/Colombo");

        long[] utc = BudgetService.windowFor(BudgetPeriod.MONTHLY, AUGUST, ZoneOffset.UTC);
        long[] local = BudgetService.windowFor(BudgetPeriod.MONTHLY, AUGUST, colombo);

        assertEquals(TimeUtils.startOfMonth(YearMonth.of(2026, 8), ZoneOffset.UTC), utc[0]);
        assertEquals(TimeUtils.startOfMonth(YearMonth.of(2026, 8), colombo), local[0]);
        assertTrue(local[0] < utc[0]);
    }

    @Test
    void windowFor_rejects_aKeyThatDoesNotMatchThePeriodType() {
        assertThrows(BadRequestException.class,
                () -> BudgetService.windowFor(BudgetPeriod.YEARLY, AUGUST, ZoneOffset.UTC));
    }
}
