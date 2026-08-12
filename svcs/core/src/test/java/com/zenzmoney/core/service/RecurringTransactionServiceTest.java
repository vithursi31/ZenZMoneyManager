package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.CategoryKind;
import com.zenzmoney.common.domain.RecurringCadence;
import com.zenzmoney.common.domain.TransactionType;
import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.common.exception.NotFoundException;
import com.zenzmoney.core.entity.Category;
import com.zenzmoney.core.entity.RecurringTransaction;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.CategoryRepository;
import com.zenzmoney.core.repository.RecurringTransactionRepository;
import com.zenzmoney.core.web.dto.CreateRecurringRequest;
import com.zenzmoney.core.web.dto.RecurringResponse;
import com.zenzmoney.core.web.dto.UpdateRecurringRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecurringTransactionServiceTest {

    private static final long DAY = 86_400_000L;

    // Known UTC midnights, so the date math is asserted against fixed epochs.
    private static final long JAN_31_2023 = 1_675_123_200_000L;
    private static final long FEB_28_2023 = 1_677_542_400_000L;
    private static final long MAR_31_2023 = 1_680_220_800_000L;
    private static final long FEB_29_2024 = 1_709_164_800_000L;
    private static final long FEB_28_2025 = 1_740_700_800_000L;

    @Mock RecurringTransactionRepository recurringRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock AccountService accountService;
    @Mock PayeeService payeeService;
    @Mock TransactionService transactionService;
    @Mock CurrentUserService currentUser;
    @InjectMocks RecurringTransactionService service;

    private User user() {
        User u = new User();
        u.setId("u1");
        u.setActiveCurrency("USD");
        return u;
    }

    private Category category(String id, CategoryKind kind) {
        Category c = new Category();
        c.setId(id);
        c.setUserId("u1");
        c.setKind(kind);
        return c;
    }

    private RecurringTransaction template(String id, RecurringCadence cadence, long nextRun, int anchorDay) {
        RecurringTransaction r = new RecurringTransaction();
        r.setId(id);
        r.setUserId("u1");
        r.setAccountId("a1");
        r.setType(TransactionType.EXPENSE);
        r.setCategoryId("c1");
        r.setAmount(100_000);
        r.setCurrency("USD");
        r.setCadence(cadence);
        r.setNextRunDate(nextRun);
        r.setAnchorDay(anchorDay);
        r.setActive(true);
        return r;
    }

    private CreateRecurringRequest expenseReq(long nextRun) {
        CreateRecurringRequest req = new CreateRecurringRequest();
        req.setType(TransactionType.EXPENSE);
        req.setCategoryId("c1");
        req.setAmount(100_000);
        req.setCadence(RecurringCadence.MONTHLY);
        req.setNextRunDate(nextRun);
        return req;
    }

    // --- CRUD ---

    @Test
    void create_expense_takesCurrencyFromUser_anchorDayFromDate_andResolvesPayee() {
        User u = user();
        when(currentUser.requireUser()).thenReturn(u);
        when(accountService.requireAccountId(u)).thenReturn("a1");
        when(categoryRepository.findByIdAndUserId("c1", "u1"))
                .thenReturn(Optional.of(category("c1", CategoryKind.EXPENSE)));
        when(payeeService.resolveOrCreate("u1", "Landlord")).thenReturn("p1");
        when(recurringRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateRecurringRequest req = expenseReq(JAN_31_2023);   // 31st ⇒ anchorDay 31
        req.setPayeeName("Landlord");
        RecurringResponse resp = service.create(req);

        ArgumentCaptor<RecurringTransaction> saved = ArgumentCaptor.forClass(RecurringTransaction.class);
        verify(recurringRepository).save(saved.capture());
        assertEquals("USD", saved.getValue().getCurrency());
        assertEquals("a1", saved.getValue().getAccountId());   // server-resolved, not requested
        assertEquals(31, saved.getValue().getAnchorDay());
        assertEquals("p1", saved.getValue().getPayeeId());
        assertTrue(saved.getValue().isActive());
        assertEquals(31, resp.getAnchorDay());
    }

    /** A subscription is just this: a recurring expense carrying its trial-end date (F-1.7). */
    @Test
    void create_subscription_keepsTrialEndDate() {
        User u = user();
        when(currentUser.requireUser()).thenReturn(u);
        when(accountService.requireAccountId(u)).thenReturn("a1");
        when(categoryRepository.findByIdAndUserId("c1", "u1"))
                .thenReturn(Optional.of(category("c1", CategoryKind.EXPENSE)));
        when(recurringRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateRecurringRequest req = expenseReq(JAN_31_2023);
        req.setTrialEndDate(FEB_28_2023);
        RecurringResponse resp = service.create(req);

        assertEquals(FEB_28_2023, resp.getTrialEndDate());
    }

    @Test
    void create_rejects_whenIncomeCategoryKindMismatch() {
        User u = user();
        when(currentUser.requireUser()).thenReturn(u);
        when(categoryRepository.findByIdAndUserId("c1", "u1"))
                .thenReturn(Optional.of(category("c1", CategoryKind.INCOME)));   // wrong kind for EXPENSE

        assertThrows(BadRequestException.class, () -> service.create(expenseReq(JAN_31_2023)));
        verify(recurringRepository, never()).save(any());
    }

    @Test
    void create_rejects_whenCategoryBelongsToAnotherUser() {
        User u = user();
        when(currentUser.requireUser()).thenReturn(u);
        when(categoryRepository.findByIdAndUserId("c1", "u1")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service.create(expenseReq(JAN_31_2023)));
        verify(recurringRepository, never()).save(any());
    }

    @Test
    void update_pausesViaActiveFalse() {
        RecurringTransaction r = template("r1", RecurringCadence.MONTHLY, JAN_31_2023, 31);
        when(currentUser.requireUserId()).thenReturn("u1");
        when(recurringRepository.findByIdAndUserId("r1", "u1")).thenReturn(Optional.of(r));
        when(recurringRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateRecurringRequest req = new UpdateRecurringRequest();
        req.setActive(false);
        RecurringResponse resp = service.update("r1", req);

        assertFalse(resp.isActive());
    }

    @Test
    void update_reschedule_reanchorsDayOfMonth() {
        RecurringTransaction r = template("r1", RecurringCadence.MONTHLY, 1_675_209_600_000L, 1); // Feb 1, anchor 1
        when(currentUser.requireUserId()).thenReturn("u1");
        when(recurringRepository.findByIdAndUserId("r1", "u1")).thenReturn(Optional.of(r));
        when(recurringRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateRecurringRequest req = new UpdateRecurringRequest();
        req.setNextRunDate(JAN_31_2023);   // move to the 31st
        RecurringResponse resp = service.update("r1", req);

        assertEquals(JAN_31_2023, resp.getNextRunDate());
        assertEquals(31, resp.getAnchorDay());
    }

    @Test
    void delete_hardDeletes() {
        RecurringTransaction r = template("r1", RecurringCadence.MONTHLY, JAN_31_2023, 31);
        when(currentUser.requireUserId()).thenReturn("u1");
        when(recurringRepository.findByIdAndUserId("r1", "u1")).thenReturn(Optional.of(r));

        service.delete("r1");

        verify(recurringRepository).delete(r);
    }

    @Test
    void get_notOwned_throwsNotFound() {
        when(currentUser.requireUserId()).thenReturn("u1");
        when(recurringRepository.findByIdAndUserId("x", "u1")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service.get("x"));
    }

    // --- generation engine ---

    @Test
    void runTemplate_generatesOneOccurrence_andAdvances() {
        long base = System.currentTimeMillis();
        RecurringTransaction r = template("r1", RecurringCadence.MONTHLY, base - 2 * DAY, 15);
        when(recurringRepository.findById("r1")).thenReturn(Optional.of(r));
        when(recurringRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        int count = service.runTemplate("r1");

        assertEquals(1, count);   // only one monthly occurrence is due
        verify(transactionService, times(1)).generateFromRecurring(eq(r), anyLong());
        assertTrue(r.getNextRunDate() > base);   // advanced into the future
    }

    @Test
    void runTemplate_catchesUpMissedOccurrences() {
        long base = System.currentTimeMillis();
        RecurringTransaction r = template("r1", RecurringCadence.WEEKLY, base - 10 * DAY, 1);
        when(recurringRepository.findById("r1")).thenReturn(Optional.of(r));
        when(recurringRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        int count = service.runTemplate("r1");

        assertEquals(2, count);   // due at -10d and -3d; next (+4d) is in the future
        verify(transactionService, times(2)).generateFromRecurring(eq(r), anyLong());
    }

    @Test
    void runTemplate_deactivates_onceNextRunPassesEndDate() {
        long base = System.currentTimeMillis();
        RecurringTransaction r = template("r1", RecurringCadence.WEEKLY, base - 2 * DAY, 1);
        r.setEndDate(base - DAY);   // one run is due, then the schedule ends
        when(recurringRepository.findById("r1")).thenReturn(Optional.of(r));
        when(recurringRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        int count = service.runTemplate("r1");

        assertEquals(1, count);
        assertFalse(r.isActive());   // deactivated because next run is past endDate
    }

    @Test
    void runTemplate_inactiveTemplate_generatesNothing() {
        RecurringTransaction r = template("r1", RecurringCadence.MONTHLY, 1L, 1);
        r.setActive(false);
        when(recurringRepository.findById("r1")).thenReturn(Optional.of(r));

        int count = service.runTemplate("r1");

        assertEquals(0, count);
        verify(transactionService, never()).generateFromRecurring(any(), anyLong());
    }

    // --- date math (§1.8): anchor-preserving month-end clamp ---

    @Test
    void advance_dailyAndWeekly_addFixedSpans() {
        assertEquals(JAN_31_2023 + DAY, RecurringTransactionService.advance(JAN_31_2023, RecurringCadence.DAILY, 31));
        assertEquals(JAN_31_2023 + 7 * DAY, RecurringTransactionService.advance(JAN_31_2023, RecurringCadence.WEEKLY, 31));
    }

    @Test
    void advance_monthly_clampsShortMonth_thenReturnsToAnchorDay() {
        long feb = RecurringTransactionService.advance(JAN_31_2023, RecurringCadence.MONTHLY, 31);
        assertEquals(FEB_28_2023, feb);   // Jan 31 → Feb 28 (clamped)

        long mar = RecurringTransactionService.advance(feb, RecurringCadence.MONTHLY, 31);
        assertEquals(MAR_31_2023, mar);   // returns to the 31st, not stuck on the 28th
    }

    @Test
    void advance_yearly_clampsLeapDay() {
        long next = RecurringTransactionService.advance(FEB_29_2024, RecurringCadence.YEARLY, 29);
        assertEquals(FEB_28_2025, next);   // Feb 29 → Feb 28 in a non-leap year
    }
}
