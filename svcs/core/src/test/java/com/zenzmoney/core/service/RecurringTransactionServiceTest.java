package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.CategoryStatus;
import com.zenzmoney.common.domain.CategoryKind;
import com.zenzmoney.common.domain.PaymentMethod;
import com.zenzmoney.common.domain.RecurringCadence;
import com.zenzmoney.common.domain.TimeUtils;
import com.zenzmoney.common.domain.TransactionType;
import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.common.exception.NotFoundException;
import com.zenzmoney.core.entity.Account;
import com.zenzmoney.core.entity.Category;
import com.zenzmoney.core.entity.RecurringTransaction;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.AccountRepository;
import com.zenzmoney.core.repository.CategoryRepository;
import com.zenzmoney.core.repository.RecurringTransactionRepository;
import com.zenzmoney.core.repository.UserRepository;
import com.zenzmoney.core.web.dto.CreateRecurringRequest;
import com.zenzmoney.core.web.dto.RecurringCreatedResponse;
import com.zenzmoney.core.web.dto.RecurringResponse;
import com.zenzmoney.core.web.dto.TransactionResponse;
import com.zenzmoney.core.web.dto.UpcomingOccurrenceResponse;
import com.zenzmoney.core.web.dto.UpdateRecurringRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    // Far-future dates, for the CRUD tests: a template dated in the past is due, and
    // creating it now posts a row (see create_postsTheOccurrence_whenTheFirstRunIsDue).
    private static final long JAN_31_2035 = 2_053_814_400_000L;

    private static final ZoneId COLOMBO = ZoneId.of("Asia/Colombo");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    // Local midnight in Colombo is 18:30 the previous day in UTC — the whole point of the
    // zone-aware date math (§1.8), and the trap the UTC version fell into.
    private static final long SEP_01_2035_COLOMBO = 2_072_197_800_000L;   // = 2035-08-31T18:30Z
    private static final long JAN_31_2035_COLOMBO = 2_053_794_600_000L;   // = 2035-01-30T18:30Z
    private static final long FEB_28_2035_COLOMBO = 2_056_213_800_000L;   // = 2035-02-27T18:30Z

    @Mock RecurringTransactionRepository recurringRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock AccountRepository accountRepository;
    @Mock UserRepository userRepository;
    @Mock AccountService accountService;
    @Mock PayeeService payeeService;
    @Mock TransactionService transactionService;
    @Mock CurrentUserService currentUser;
    @InjectMocks RecurringTransactionService service;

    private User user() {
        return user("UTC");
    }

    private User user(String timezone) {
        User u = new User();
        u.setId("u1");
        u.setActiveCurrency("USD");
        u.setTimezone(timezone);
        return u;
    }

    /** The account is the only holder of the currency (§1.4) — the template has none. */
    private Account account(String id, String currency) {
        Account a = new Account();
        a.setId(id);
        a.setUserId("u1");
        a.setCurrency(currency);
        return a;
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

    /** Stubs the create path for {@code user}, up to (not including) the save. */
    private void stubCreateFor(User u, CategoryKind kind) {
        when(currentUser.requireUser()).thenReturn(u);
        when(categoryRepository.findByIdAndUserIdAndStatus("c1", "u1", CategoryStatus.ACTIVE))
                .thenReturn(Optional.of(category("c1", kind)));
        when(accountService.provision(u)).thenReturn(account("a1", "USD"));
        when(recurringRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // --- CRUD ---

    @Test
    void create_expense_takesCurrencyFromUser_anchorDayFromDate_andResolvesPayee() {
        User u = user();
        stubCreateFor(u, CategoryKind.EXPENSE);
        when(payeeService.resolveOrCreate("u1", "Landlord")).thenReturn("p1");

        CreateRecurringRequest req = expenseReq(JAN_31_2035);   // 31st ⇒ anchorDay 31
        req.setPayeeName("Landlord");
        req.setPaymentMethod(PaymentMethod.CARD);
        RecurringCreatedResponse resp = service.create(req);

        ArgumentCaptor<RecurringTransaction> saved = ArgumentCaptor.forClass(RecurringTransaction.class);
        verify(recurringRepository).save(saved.capture());
        assertEquals("a1", saved.getValue().getAccountId());   // server-resolved, not requested
        assertEquals(31, saved.getValue().getAnchorDay());
        assertEquals("p1", saved.getValue().getPayeeId());
        assertEquals(PaymentMethod.CARD, saved.getValue().getPaymentMethod());
        assertTrue(saved.getValue().isActive());
        assertEquals(31, resp.getTemplate().getAnchorDay());
        assertEquals("USD", resp.getTemplate().getCurrency());   // read off the account
        assertEquals(PaymentMethod.CARD, resp.getTemplate().getPaymentMethod());
        assertNull(resp.getPosted());                          // not due yet, so nothing posted
    }

    /** A subscription is just this: a recurring expense carrying its trial-end date (F-1.7). */
    @Test
    void create_subscription_keepsTrialEndDate() {
        User u = user();
        stubCreateFor(u, CategoryKind.EXPENSE);

        CreateRecurringRequest req = expenseReq(JAN_31_2035);
        req.setTrialEndDate(JAN_31_2035 + 14 * DAY);
        RecurringCreatedResponse resp = service.create(req);

        assertEquals(JAN_31_2035 + 14 * DAY, resp.getTemplate().getTrialEndDate());
    }

    /**
     * The anchor is the day the <em>user</em> picked, so it is read in the user's zone. Local
     * midnight on the 1st in Colombo is 18:30 on the last day of the previous month in UTC —
     * read as UTC this template would anchor to the 31st and generate into the wrong month.
     */
    @Test
    void create_anchorsToTheDayInTheUsersOwnTimezone() {
        User u = user("Asia/Colombo");
        stubCreateFor(u, CategoryKind.EXPENSE);

        RecurringCreatedResponse resp = service.create(expenseReq(SEP_01_2035_COLOMBO));

        assertEquals(1, resp.getTemplate().getAnchorDay());
    }

    /** A template whose first run has already arrived posts that row with the response. */
    @Test
    void create_postsTheOccurrence_whenTheFirstRunIsDue() {
        User u = user();
        stubCreateFor(u, CategoryKind.EXPENSE);
        TransactionResponse posted = TransactionResponse.of(new com.zenzmoney.core.entity.Transaction());
        when(transactionService.generateFromRecurring(any(), anyLong(), eq("USD"))).thenReturn(posted);

        long dueYesterday = System.currentTimeMillis() - DAY;
        RecurringCreatedResponse resp = service.create(expenseReq(dueYesterday));

        assertEquals(posted, resp.getPosted());
        // Exactly one, even for a badly backdated template: the rest is the scheduler's job,
        // so the request stays bounded.
        verify(transactionService, times(1)).generateFromRecurring(any(), eq(dueYesterday), eq("USD"));
        assertTrue(resp.getTemplate().getNextRunDate() > dueYesterday);
    }

    @Test
    void create_backdatedTemplate_postsOnlyOneOccurrenceInTheRequest() {
        User u = user();
        stubCreateFor(u, CategoryKind.EXPENSE);
        when(transactionService.generateFromRecurring(any(), anyLong(), eq("USD")))
                .thenReturn(TransactionResponse.of(new com.zenzmoney.core.entity.Transaction()));

        CreateRecurringRequest req = expenseReq(System.currentTimeMillis() - 400 * DAY);
        req.setCadence(RecurringCadence.DAILY);
        service.create(req);

        verify(transactionService, times(1)).generateFromRecurring(any(), anyLong(), any());
    }

    @Test
    void create_rejects_whenIncomeCategoryKindMismatch() {
        User u = user();
        when(currentUser.requireUser()).thenReturn(u);
        when(categoryRepository.findByIdAndUserIdAndStatus("c1", "u1", CategoryStatus.ACTIVE))
                .thenReturn(Optional.of(category("c1", CategoryKind.INCOME)));   // wrong kind for EXPENSE

        assertThrows(BadRequestException.class, () -> service.create(expenseReq(JAN_31_2035)));
        verify(recurringRepository, never()).save(any());
    }

    @Test
    void create_rejects_whenCategoryBelongsToAnotherUser() {
        User u = user();
        when(currentUser.requireUser()).thenReturn(u);
        when(categoryRepository.findByIdAndUserIdAndStatus("c1", "u1", CategoryStatus.ACTIVE)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service.create(expenseReq(JAN_31_2035)));
        verify(recurringRepository, never()).save(any());
    }

    @Test
    void update_pausesViaActiveFalse() {
        RecurringTransaction r = template("r1", RecurringCadence.MONTHLY, JAN_31_2035, 31);
        when(currentUser.requireUser()).thenReturn(user());
        when(recurringRepository.findByIdAndUserId("r1", "u1")).thenReturn(Optional.of(r));
        when(accountRepository.findByIdAndUserId("a1", "u1")).thenReturn(Optional.of(account("a1", "USD")));
        when(recurringRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateRecurringRequest req = new UpdateRecurringRequest();
        req.setActive(false);
        RecurringResponse resp = service.update("r1", req);

        assertFalse(resp.isActive());
    }

    @Test
    void update_reschedule_reanchorsDayOfMonth() {
        RecurringTransaction r = template("r1", RecurringCadence.MONTHLY, 1_675_209_600_000L, 1); // Feb 1, anchor 1
        when(currentUser.requireUser()).thenReturn(user());
        when(recurringRepository.findByIdAndUserId("r1", "u1")).thenReturn(Optional.of(r));
        when(accountRepository.findByIdAndUserId("a1", "u1")).thenReturn(Optional.of(account("a1", "USD")));
        when(recurringRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateRecurringRequest req = new UpdateRecurringRequest();
        req.setNextRunDate(JAN_31_2035);   // move to the 31st
        RecurringResponse resp = service.update("r1", req);

        assertEquals(JAN_31_2035, resp.getNextRunDate());
        assertEquals(31, resp.getAnchorDay());
    }

    /** A reschedule re-reads the anchor, and does it in the caller's zone like the create did. */
    @Test
    void update_reschedule_reanchorsInTheUsersOwnTimezone() {
        RecurringTransaction r = template("r1", RecurringCadence.MONTHLY, JAN_31_2035, 31);
        when(currentUser.requireUser()).thenReturn(user("Asia/Colombo"));
        when(recurringRepository.findByIdAndUserId("r1", "u1")).thenReturn(Optional.of(r));
        when(accountRepository.findByIdAndUserId("a1", "u1")).thenReturn(Optional.of(account("a1", "USD")));
        when(recurringRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateRecurringRequest req = new UpdateRecurringRequest();
        req.setNextRunDate(SEP_01_2035_COLOMBO);
        RecurringResponse resp = service.update("r1", req);

        assertEquals(1, resp.getAnchorDay());
    }

    @Test
    void update_setsPaymentMethod() {
        RecurringTransaction r = template("r1", RecurringCadence.MONTHLY, JAN_31_2035, 31);
        when(currentUser.requireUser()).thenReturn(user());
        when(recurringRepository.findByIdAndUserId("r1", "u1")).thenReturn(Optional.of(r));
        when(accountRepository.findByIdAndUserId("a1", "u1")).thenReturn(Optional.of(account("a1", "USD")));
        when(recurringRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateRecurringRequest req = new UpdateRecurringRequest();
        req.setPaymentMethod(PaymentMethod.BANK_TRANSFER);

        assertEquals(PaymentMethod.BANK_TRANSFER, service.update("r1", req).getPaymentMethod());
    }

    @Test
    void delete_hardDeletes() {
        RecurringTransaction r = template("r1", RecurringCadence.MONTHLY, JAN_31_2035, 31);
        when(currentUser.requireUser()).thenReturn(user());
        when(recurringRepository.findByIdAndUserId("r1", "u1")).thenReturn(Optional.of(r));
        when(accountRepository.findByIdAndUserId("a1", "u1")).thenReturn(Optional.of(account("a1", "USD")));

        service.delete("r1");

        verify(recurringRepository).delete(r);
    }

    /**
     * The currency is the account's, not the user's active one. They agree today, so this
     * pins which of the two is actually being read — the whole point of dropping the
     * template's own column.
     */
    @Test
    void create_takesTheCurrencyFromTheAccount_notTheUsersActiveOne() {
        User u = user();
        u.setActiveCurrency("USD");
        when(currentUser.requireUser()).thenReturn(u);
        when(categoryRepository.findByIdAndUserIdAndStatus("c1", "u1", CategoryStatus.ACTIVE))
                .thenReturn(Optional.of(category("c1", CategoryKind.EXPENSE)));
        when(accountService.provision(u)).thenReturn(account("a1", "LKR"));
        when(recurringRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertEquals("LKR", service.create(expenseReq(JAN_31_2035)).getTemplate().getCurrency());
    }

    /** A listing must not 404 wholesale because one template's account cannot be read. */
    @Test
    void list_fallsBackToTheUsersActiveCurrency_whenTheAccountIsMissing() {
        User u = user();
        when(currentUser.requireUser()).thenReturn(u);
        when(accountRepository.findByUserId("u1")).thenReturn(List.of());
        when(recurringRepository.findByUserId("u1"))
                .thenReturn(List.of(template("r1", RecurringCadence.MONTHLY, JAN_31_2035, 31)));

        assertEquals("USD", service.list(false).get(0).getCurrency());
    }

    /**
     * The write path does the opposite: rather than guess a currency for money it is about
     * to post, it fails, and the scheduler isolates and logs that one template.
     */
    @Test
    void runTemplate_failsLoudly_whenTheAccountCannotBeRead() {
        RecurringTransaction r = template("r1", RecurringCadence.MONTHLY,
                System.currentTimeMillis() - DAY, 15);
        when(recurringRepository.findById("r1")).thenReturn(Optional.of(r));
        when(userRepository.findById("u1")).thenReturn(Optional.of(user()));
        when(accountRepository.findByIdAndUserId("a1", "u1")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service.runTemplate("r1"));
        verify(transactionService, never()).generateFromRecurring(any(), anyLong(), any());
    }

    @Test
    void get_notOwned_throwsNotFound() {
        when(currentUser.requireUser()).thenReturn(user());
        when(recurringRepository.findByIdAndUserId("x", "u1")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service.get("x"));
    }

    // --- upcoming payments: a projection, never a row ---

    @Test
    void upcoming_projectsEachOccurrenceInTheWindow_andWritesNothing() {
        User u = user();
        when(currentUser.requireUser()).thenReturn(u);
        when(accountRepository.findByUserId("u1")).thenReturn(List.of(account("a1", "USD")));
        long now = System.currentTimeMillis();
        RecurringTransaction spotify = template("r1", RecurringCadence.DAILY, now + 2 * DAY, 1);
        when(recurringRepository.findByUserIdAndActiveTrue("u1")).thenReturn(List.of(spotify));

        List<UpcomingOccurrenceResponse> out = service.upcoming(3);

        assertFalse(out.isEmpty());
        assertTrue(out.stream().allMatch(o -> "r1".equals(o.getRecurringId())));
        // Sorted by due date, and nothing was created or advanced.
        assertTrue(out.get(0).getDueDate() <= out.get(out.size() - 1).getDueDate());
        assertEquals(now + 2 * DAY, spotify.getNextRunDate());
        verify(transactionService, never()).generateFromRecurring(any(), anyLong(), any());
        verify(recurringRepository, never()).save(any());
    }

    /**
     * The window runs to the end of the target day, so a renewal on the 24th is visible on
     * the 21st however late in the day it falls — a rolling now+72h window would miss it.
     */
    @Test
    void upcoming_includesTheWholeOfTheFinalDay() {
        User u = user();
        when(currentUser.requireUser()).thenReturn(u);
        when(accountRepository.findByUserId("u1")).thenReturn(List.of(account("a1", "USD")));
        long startOfToday = TimeUtils.startOfDay(System.currentTimeMillis(), ZoneOffset.UTC);
        long lateOnDayThree = startOfToday + 3 * DAY + 20 * 3_600_000L;   // 20:00 on the third day
        when(recurringRepository.findByUserIdAndActiveTrue("u1"))
                .thenReturn(List.of(template("r1", RecurringCadence.MONTHLY, lateOnDayThree, 1)));

        assertEquals(1, service.upcoming(3).size());
    }

    /** And the far end is exclusive: midnight opening the fourth day is a day too far. */
    @Test
    void upcoming_excludesTheInstantTheWindowCloses() {
        User u = user();
        when(currentUser.requireUser()).thenReturn(u);
        when(accountRepository.findByUserId("u1")).thenReturn(List.of(account("a1", "USD")));
        long startOfDayFour = TimeUtils.startOfDay(System.currentTimeMillis(), ZoneOffset.UTC) + 4 * DAY;
        when(recurringRepository.findByUserIdAndActiveTrue("u1"))
                .thenReturn(List.of(template("r1", RecurringCadence.MONTHLY, startOfDayFour, 1)));

        assertTrue(service.upcoming(3).isEmpty());
    }

    @Test
    void upcoming_excludesWhatFallsOutsideTheWindow() {
        User u = user();
        when(currentUser.requireUser()).thenReturn(u);
        when(accountRepository.findByUserId("u1")).thenReturn(List.of(account("a1", "USD")));
        when(recurringRepository.findByUserIdAndActiveTrue("u1"))
                .thenReturn(List.of(template("r1", RecurringCadence.MONTHLY,
                        System.currentTimeMillis() + 20 * DAY, 1)));

        assertTrue(service.upcoming(3).isEmpty());
    }

    /**
     * An occurrence whose date has passed exists nowhere else — the template's nextRunDate
     * has not advanced past it — so it stays listed, flagged {@code due}, until the
     * scheduler posts it.
     */
    @Test
    void upcoming_keepsAnOccurrenceThatIsDueButNotYetPosted() {
        User u = user();
        long overdue = System.currentTimeMillis() - 2 * DAY;
        when(currentUser.requireUser()).thenReturn(u);
        when(accountRepository.findByUserId("u1")).thenReturn(List.of(account("a1", "USD")));
        when(recurringRepository.findByUserIdAndActiveTrue("u1"))
                .thenReturn(List.of(template("r1", RecurringCadence.MONTHLY, overdue, 1)));

        List<UpcomingOccurrenceResponse> out = service.upcoming(3);

        // Asserted by identity, not by count: run this on the 1st and the template's own
        // anchor day puts a *second*, legitimately upcoming occurrence inside the same
        // three-day window. What this test is about is that the overdue one survives.
        assertTrue(out.stream().anyMatch(o -> o.getDueDate() == overdue && o.isDue()),
                () -> "the unposted overdue occurrence is missing from " + out.size() + " results");
    }

    @Test
    void upcoming_stopsAtTheTemplatesEndDate() {
        User u = user();
        when(currentUser.requireUser()).thenReturn(u);
        when(accountRepository.findByUserId("u1")).thenReturn(List.of(account("a1", "USD")));
        long now = System.currentTimeMillis();
        RecurringTransaction r = template("r1", RecurringCadence.DAILY, now + DAY, 1);
        r.setEndDate(now + 2 * DAY);
        when(recurringRepository.findByUserIdAndActiveTrue("u1")).thenReturn(List.of(r));

        assertEquals(2, service.upcoming(5).size());   // +1d and +2d, then the schedule ends
    }

    @Test
    void upcoming_capsOneTemplatesProjection() {
        User u = user();
        when(currentUser.requireUser()).thenReturn(u);
        when(accountRepository.findByUserId("u1")).thenReturn(List.of(account("a1", "USD")));
        when(recurringRepository.findByUserIdAndActiveTrue("u1"))
                .thenReturn(List.of(template("r1", RecurringCadence.DAILY, System.currentTimeMillis(), 1)));

        assertEquals(RecurringTransactionService.MAX_UPCOMING_PER_TEMPLATE,
                service.upcoming(RecurringTransactionService.MAX_UPCOMING_DAYS).size());
    }

    @Test
    void upcoming_flagsATrialEndingInsideTheWindow() {
        User u = user();
        when(currentUser.requireUser()).thenReturn(u);
        when(accountRepository.findByUserId("u1")).thenReturn(List.of(account("a1", "USD")));
        long now = System.currentTimeMillis();
        RecurringTransaction r = template("r1", RecurringCadence.MONTHLY, now + DAY, 1);
        r.setTrialEndDate(now + 2 * DAY);
        when(recurringRepository.findByUserIdAndActiveTrue("u1")).thenReturn(List.of(r));

        assertTrue(service.upcoming(3).get(0).isTrialEnding());
    }

    @Test
    void upcoming_defaultsToThreeDays_whenNoWindowIsGiven() {
        User u = user();
        when(currentUser.requireUser()).thenReturn(u);
        when(accountRepository.findByUserId("u1")).thenReturn(List.of(account("a1", "USD")));
        when(recurringRepository.findByUserIdAndActiveTrue("u1"))
                .thenReturn(List.of(template("r1", RecurringCadence.MONTHLY,
                        System.currentTimeMillis() + 6 * DAY, 1)));

        assertTrue(service.upcoming(null).isEmpty());   // 6 days out, default window is 3
    }

    @Test
    void upcoming_rejectsAWindowOutsideTheAllowedRange() {
        when(currentUser.requireUser()).thenReturn(user());

        assertThrows(BadRequestException.class, () -> service.upcoming(0));
        assertThrows(BadRequestException.class,
                () -> service.upcoming(RecurringTransactionService.MAX_UPCOMING_DAYS + 1));
        verify(recurringRepository, never()).findByUserIdAndActiveTrue(any());
    }

    // --- generation engine ---

    @Test
    void runTemplate_generatesOneOccurrence_andAdvances() {
        long base = System.currentTimeMillis();
        RecurringTransaction r = template("r1", RecurringCadence.MONTHLY, base - 2 * DAY, 15);
        when(recurringRepository.findById("r1")).thenReturn(Optional.of(r));
        when(userRepository.findById("u1")).thenReturn(Optional.of(user()));
        when(accountRepository.findByIdAndUserId("a1", "u1")).thenReturn(Optional.of(account("a1", "USD")));
        when(recurringRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        int count = service.runTemplate("r1");

        assertEquals(1, count);   // only one monthly occurrence is due
        verify(transactionService, times(1)).generateFromRecurring(eq(r), anyLong(), eq("USD"));
        assertTrue(r.getNextRunDate() > base);   // advanced into the future
    }

    @Test
    void runTemplate_catchesUpMissedOccurrences() {
        long base = System.currentTimeMillis();
        RecurringTransaction r = template("r1", RecurringCadence.WEEKLY, base - 10 * DAY, 1);
        when(recurringRepository.findById("r1")).thenReturn(Optional.of(r));
        when(userRepository.findById("u1")).thenReturn(Optional.of(user()));
        when(accountRepository.findByIdAndUserId("a1", "u1")).thenReturn(Optional.of(account("a1", "USD")));
        when(recurringRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        int count = service.runTemplate("r1");

        assertEquals(2, count);   // due at -10d and -3d; next (+4d) is in the future
        verify(transactionService, times(2)).generateFromRecurring(eq(r), anyLong(), eq("USD"));
    }

    @Test
    void runTemplate_deactivates_onceNextRunPassesEndDate() {
        long base = System.currentTimeMillis();
        RecurringTransaction r = template("r1", RecurringCadence.WEEKLY, base - 2 * DAY, 1);
        r.setEndDate(base - DAY);   // one run is due, then the schedule ends
        when(recurringRepository.findById("r1")).thenReturn(Optional.of(r));
        when(userRepository.findById("u1")).thenReturn(Optional.of(user()));
        when(accountRepository.findByIdAndUserId("a1", "u1")).thenReturn(Optional.of(account("a1", "USD")));
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
        verify(transactionService, never()).generateFromRecurring(any(), anyLong(), any());
    }

    /**
     * The scheduler has no caller, so the zone the schedule advances in comes from the
     * template's owner. A Colombo template on the 1st stays on the 1st.
     */
    @Test
    void runTemplate_advancesInTheOwnersTimezone() {
        RecurringTransaction r = template("r1", RecurringCadence.MONTHLY, SEP_01_2035_COLOMBO, 1);
        r.setNextRunDate(System.currentTimeMillis() - DAY);
        r.setAnchorDay(1);
        when(recurringRepository.findById("r1")).thenReturn(Optional.of(r));
        when(userRepository.findById("u1")).thenReturn(Optional.of(user("Asia/Colombo")));
        when(accountRepository.findByIdAndUserId("a1", "u1")).thenReturn(Optional.of(account("a1", "LKR")));
        when(recurringRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.runTemplate("r1");

        assertEquals(1, Instant.ofEpochMilli(r.getNextRunDate()).atZone(COLOMBO).getDayOfMonth());
    }

    /** A missing owner row cannot stop generation; UTC is the documented fallback. */
    @Test
    void runTemplate_fallsBackToUtc_whenTheOwnerCannotBeRead() {
        RecurringTransaction r = template("r1", RecurringCadence.MONTHLY,
                System.currentTimeMillis() - DAY, 15);
        when(recurringRepository.findById("r1")).thenReturn(Optional.of(r));
        when(userRepository.findById("u1")).thenReturn(Optional.empty());
        when(accountRepository.findByIdAndUserId("a1", "u1")).thenReturn(Optional.of(account("a1", "USD")));
        when(recurringRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertEquals(1, service.runTemplate("r1"));
    }

    // --- date math (§1.8): anchor-preserving month-end clamp ---

    @Test
    void advance_dailyAndWeekly_addFixedSpans() {
        assertEquals(JAN_31_2023 + DAY,
                RecurringTransactionService.advance(JAN_31_2023, RecurringCadence.DAILY, 31, ZoneOffset.UTC));
        assertEquals(JAN_31_2023 + 7 * DAY,
                RecurringTransactionService.advance(JAN_31_2023, RecurringCadence.WEEKLY, 31, ZoneOffset.UTC));
    }

    @Test
    void advance_monthly_clampsShortMonth_thenReturnsToAnchorDay() {
        long feb = RecurringTransactionService.advance(JAN_31_2023, RecurringCadence.MONTHLY, 31, ZoneOffset.UTC);
        assertEquals(FEB_28_2023, feb);   // Jan 31 → Feb 28 (clamped)

        long mar = RecurringTransactionService.advance(feb, RecurringCadence.MONTHLY, 31, ZoneOffset.UTC);
        assertEquals(MAR_31_2023, mar);   // returns to the 31st, not stuck on the 28th
    }

    @Test
    void advance_yearly_clampsLeapDay() {
        long next = RecurringTransactionService.advance(FEB_29_2024, RecurringCadence.YEARLY, 29, ZoneOffset.UTC);
        assertEquals(FEB_28_2025, next);   // Feb 29 → Feb 28 in a non-leap year
    }

    /**
     * The month-end clamp is applied to the <em>user's</em> calendar: a Colombo bill on the
     * 31st lands on Feb 28 local. Read in UTC the same template sits on Jan 30 and would
     * clamp to Feb 28 <em>UTC</em>, which is March 1 in Colombo — a row in the wrong month.
     */
    @Test
    void advance_monthly_clampsInTheGivenZone_notUtc() {
        long next = RecurringTransactionService.advance(
                JAN_31_2035_COLOMBO, RecurringCadence.MONTHLY, 31, COLOMBO);

        assertEquals(FEB_28_2035_COLOMBO, next);
        assertNotEquals(next, RecurringTransactionService.advance(
                JAN_31_2035_COLOMBO, RecurringCadence.MONTHLY, 31, ZoneOffset.UTC));
    }

    /**
     * A calendar week, not 7×24h: across a DST shift the template keeps the local time of
     * day the user picked, so a subscription billed at 20:00 is never billed at 21:00.
     */
    @Test
    void advance_weekly_keepsLocalTimeOfDayAcrossADstShift() {
        long marchSeventh2035At2000NewYork = 2_056_928_400_000L;

        long next = RecurringTransactionService.advance(
                marchSeventh2035At2000NewYork, RecurringCadence.WEEKLY, 7, NEW_YORK);

        assertEquals(20, Instant.ofEpochMilli(next).atZone(NEW_YORK).getHour());
        assertNotEquals(marchSeventh2035At2000NewYork + 7 * DAY, next);
    }
}
