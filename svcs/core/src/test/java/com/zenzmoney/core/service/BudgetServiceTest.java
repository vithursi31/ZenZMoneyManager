package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.BudgetStatus;
import com.zenzmoney.common.domain.BudgetPeriod;
import com.zenzmoney.common.domain.CategoryKind;
import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.common.exception.NotFoundException;
import com.zenzmoney.core.entity.Budget;
import com.zenzmoney.core.entity.Category;
import com.zenzmoney.core.entity.User;
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

    private static final long DAY = 86_400_000L;
    private static final long ANCHOR = 1_700_000_000_000L;   // 2023-11-14T22:13:20Z

    @Mock BudgetRepository budgetRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock CurrentUserService currentUser;
    @InjectMocks BudgetService budgetService;

    private User user(String id, String currency) {
        User u = new User();
        u.setId(id);
        u.setActiveCurrency(currency);
        return u;
    }

    private Category category(String id, String userId, CategoryKind kind) {
        Category c = new Category();
        c.setId(id);
        c.setUserId(userId);
        c.setName("Food & Drinks");
        c.setKind(kind);
        return c;
    }

    private Budget owned(String id, String userId, String categoryId) {
        Budget b = new Budget();
        b.setId(id);
        b.setUserId(userId);
        b.setCategoryId(categoryId);
        b.setPeriod(BudgetPeriod.MONTHLY);
        b.setAmountLimit(50_000);
        b.setCurrency("USD");
        b.setStartDate(ANCHOR);
        b.setStatus(BudgetStatus.ACTIVE);
        return b;
    }

    private CreateBudgetRequest createReq(String categoryId, BudgetPeriod period, long limit) {
        CreateBudgetRequest r = new CreateBudgetRequest();
        r.setCategoryId(categoryId);
        r.setPeriod(period);
        r.setAmountLimit(limit);
        r.setStartDate(ANCHOR);
        return r;
    }

    @Test
    void create_categoryBudget_usesActiveCurrency_andComputesSpentForCategory() {
        when(currentUser.requireUser()).thenReturn(user("u1", "USD"));
        when(categoryRepository.findByIdAndUserId("c1", "u1"))
                .thenReturn(Optional.of(category("c1", "u1", CategoryKind.EXPENSE)));
        when(budgetRepository.findByUserId("u1")).thenReturn(List.of());
        when(budgetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.sumExpenseByCategoryInWindow(eq("u1"), eq("c1"), anyLong(), anyLong()))
                .thenReturn(12_000L);

        BudgetResponse resp = budgetService.create(createReq("c1", BudgetPeriod.MONTHLY, 50_000));

        ArgumentCaptor<Budget> saved = ArgumentCaptor.forClass(Budget.class);
        verify(budgetRepository).save(saved.capture());
        assertEquals("u1", saved.getValue().getUserId());
        assertEquals("USD", saved.getValue().getCurrency());
        assertEquals(BudgetStatus.ACTIVE, saved.getValue().getStatus());
        assertEquals(12_000L, resp.getSpent());
        assertEquals(38_000L, resp.getRemaining());   // 50_000 - 12_000
    }

    @Test
    void create_overallBudget_hasNullCategory_andSumsAllExpense() {
        when(currentUser.requireUser()).thenReturn(user("u1", "USD"));
        when(budgetRepository.findByUserId("u1")).thenReturn(List.of());
        when(budgetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.sumExpenseInWindow(eq("u1"), anyLong(), anyLong())).thenReturn(9_000L);

        BudgetResponse resp = budgetService.create(createReq(null, BudgetPeriod.MONTHLY, 30_000));

        assertNull(resp.getCategoryId());
        assertEquals(9_000L, resp.getSpent());
        verify(categoryRepository, never()).findByIdAndUserId(any(), any());
    }

    @Test
    void create_rejects_whenCategoryIsIncome() {
        when(currentUser.requireUser()).thenReturn(user("u1", "USD"));
        when(categoryRepository.findByIdAndUserId("c1", "u1"))
                .thenReturn(Optional.of(category("c1", "u1", CategoryKind.INCOME)));

        assertThrows(BadRequestException.class,
                () -> budgetService.create(createReq("c1", BudgetPeriod.MONTHLY, 50_000)));
        verify(budgetRepository, never()).save(any());
    }

    @Test
    void create_rejects_whenCategoryNotFound() {
        when(currentUser.requireUser()).thenReturn(user("u1", "USD"));
        when(categoryRepository.findByIdAndUserId("c1", "u1")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class,
                () -> budgetService.create(createReq("c1", BudgetPeriod.MONTHLY, 50_000)));
        verify(budgetRepository, never()).save(any());
    }

    @Test
    void create_rejects_duplicateActiveForSameCategoryAndPeriod() {
        when(currentUser.requireUser()).thenReturn(user("u1", "USD"));
        when(categoryRepository.findByIdAndUserId("c1", "u1"))
                .thenReturn(Optional.of(category("c1", "u1", CategoryKind.EXPENSE)));
        when(budgetRepository.findByUserId("u1")).thenReturn(List.of(owned("b1", "u1", "c1")));

        assertThrows(BadRequestException.class,
                () -> budgetService.create(createReq("c1", BudgetPeriod.MONTHLY, 50_000)));
        verify(budgetRepository, never()).save(any());
    }

    @Test
    void create_allowsSecondBudget_whenPeriodDiffers() {
        when(currentUser.requireUser()).thenReturn(user("u1", "USD"));
        when(categoryRepository.findByIdAndUserId("c1", "u1"))
                .thenReturn(Optional.of(category("c1", "u1", CategoryKind.EXPENSE)));
        when(budgetRepository.findByUserId("u1")).thenReturn(List.of(owned("b1", "u1", "c1"))); // MONTHLY
        when(budgetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.sumExpenseByCategoryInWindow(eq("u1"), eq("c1"), anyLong(), anyLong()))
                .thenReturn(0L);

        BudgetResponse resp = budgetService.create(createReq("c1", BudgetPeriod.WEEKLY, 10_000));

        assertEquals(BudgetPeriod.WEEKLY, resp.getPeriod());
    }

    @Test
    void update_changesLimitAndRollover() {
        Budget b = owned("b1", "u1", "c1");
        when(currentUser.requireUserId()).thenReturn("u1");
        when(budgetRepository.findByIdAndUserId("b1", "u1")).thenReturn(Optional.of(b));
        when(budgetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
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
        Budget b = owned("b1", "u1", "c1");
        when(currentUser.requireUserId()).thenReturn("u1");
        when(budgetRepository.findByIdAndUserId("b1", "u1")).thenReturn(Optional.of(b));
        when(budgetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.sumExpenseByCategoryInWindow(eq("u1"), eq("c1"), anyLong(), anyLong()))
                .thenReturn(0L);

        BudgetResponse resp = budgetService.archive("b1");

        assertEquals(BudgetStatus.ARCHIVED, resp.getStatus());
    }

    @Test
    void delete_hardDeletes() {
        Budget b = owned("b1", "u1", "c1");
        when(currentUser.requireUserId()).thenReturn("u1");
        when(budgetRepository.findByIdAndUserId("b1", "u1")).thenReturn(Optional.of(b));

        budgetService.delete("b1");

        verify(budgetRepository).delete(b);
    }

    @Test
    void get_notOwned_throwsNotFound() {
        when(currentUser.requireUserId()).thenReturn("u1");
        when(budgetRepository.findByIdAndUserId("x", "u1")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> budgetService.get("x"));
    }

    @Test
    void currentWindow_weekly_firstAndCrossingWindows() {
        long[] first = BudgetService.currentWindow(ANCHOR, BudgetPeriod.WEEKLY, ANCHOR + 3 * DAY);
        assertEquals(ANCHOR, first[0]);
        assertEquals(ANCHOR + 7 * DAY, first[1]);

        long[] second = BudgetService.currentWindow(ANCHOR, BudgetPeriod.WEEKLY, ANCHOR + 8 * DAY);
        assertEquals(ANCHOR + 7 * DAY, second[0]);
        assertEquals(ANCHOR + 14 * DAY, second[1]);
    }

    @Test
    void currentWindow_beforeAnchor_returnsFirstWindow() {
        long[] w = BudgetService.currentWindow(ANCHOR, BudgetPeriod.WEEKLY, ANCHOR - 3 * DAY);
        assertEquals(ANCHOR, w[0]);
        assertEquals(ANCHOR + 7 * DAY, w[1]);
    }

    @Test
    void currentWindow_monthly_containsNow_andAnchorsFirstWindow() {
        long[] w = BudgetService.currentWindow(ANCHOR, BudgetPeriod.MONTHLY, ANCHOR);
        assertEquals(ANCHOR, w[0]);          // now == anchor ⇒ first window
        assertTrue(w[1] > ANCHOR);           // month length honored
        assertTrue(ANCHOR >= w[0] && ANCHOR < w[1]);
    }
}
