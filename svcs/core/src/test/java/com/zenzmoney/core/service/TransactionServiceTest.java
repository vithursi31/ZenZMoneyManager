package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.CategoryKind;
import com.zenzmoney.common.domain.TransactionType;
import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.common.exception.NotFoundException;
import com.zenzmoney.core.entity.Category;
import com.zenzmoney.core.entity.RecurringTransaction;
import com.zenzmoney.core.entity.Transaction;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.CategoryRepository;
import com.zenzmoney.core.repository.TransactionRepository;
import com.zenzmoney.core.web.dto.CreateTransactionRequest;
import com.zenzmoney.core.web.dto.TransactionResponse;
import com.zenzmoney.core.web.dto.UpdateTransactionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock TransactionRepository transactionRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock AccountService accountService;
    @Mock PayeeService payeeService;
    @Mock CurrentUserService currentUser;
    @InjectMocks TransactionService transactionService;

    private static final long JAN_2026 = 1_767_225_600_000L;   // 2026-01-01T00:00:00Z

    /** Leaves User's own default timezone (UTC) in place. */
    private User user() {
        User u = new User();
        u.setId("u1");
        u.setActiveCurrency("USD");
        return u;
    }

    private User user(String timezone) {
        User u = user();
        u.setTimezone(timezone);
        return u;
    }

    private Category category(String id, CategoryKind kind) {
        Category c = new Category();
        c.setId(id);
        c.setUserId("u1");
        c.setKind(kind);
        return c;
    }

    private CreateTransactionRequest req(TransactionType type, String categoryId, long amount) {
        CreateTransactionRequest r = new CreateTransactionRequest();
        r.setType(type);
        r.setCategoryId(categoryId);
        r.setAmount(amount);
        r.setTxnDate(JAN_2026);
        return r;
    }

    /** The caller is authenticated, has an account, and owns the category. */
    private void stubHappyPath(CategoryKind kind) {
        User u = user();
        when(currentUser.requireUser()).thenReturn(u);
        when(accountService.requireAccountId(u)).thenReturn("a1");
        when(categoryRepository.findByIdAndUserId("c1", "u1")).thenReturn(Optional.of(category("c1", kind)));
        when(transactionRepository.save(any())).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            t.setId("t1");
            return t;
        });
    }

    @Test
    void create_income_persistsWithUsersCurrency() {
        stubHappyPath(CategoryKind.INCOME);

        TransactionResponse resp = transactionService.create(req(TransactionType.INCOME, "c1", 50_000));

        assertEquals(TransactionType.INCOME, resp.getType());
        assertEquals(50_000, resp.getAmount());
        assertEquals("USD", resp.getCurrency());
    }

    /**
     * The account is the server's business, not the client's: nothing in the request
     * names one, and the row still lands on the caller's account (§1.4).
     */
    @Test
    void create_resolvesAccountServerSide() {
        stubHappyPath(CategoryKind.EXPENSE);

        transactionService.create(req(TransactionType.EXPENSE, "c1", 20_000));

        ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(saved.capture());
        assertEquals("a1", saved.getValue().getAccountId());
        assertEquals("u1", saved.getValue().getUserId());
    }

    /** No balance is written back — that is the whole point of the derived position (§1.10). */
    @Test
    void create_writesNothingBackToTheAccount() {
        stubHappyPath(CategoryKind.EXPENSE);

        transactionService.create(req(TransactionType.EXPENSE, "c1", 20_000));

        verify(accountService).requireAccountId(any());   // resolved, and that is all
        verify(accountService, never()).current();
    }

    @Test
    void create_resolvesPayee_ontoTransaction() {
        stubHappyPath(CategoryKind.EXPENSE);
        when(payeeService.resolveOrCreate(eq("u1"), eq("Keells"))).thenReturn("p1");

        CreateTransactionRequest r = req(TransactionType.EXPENSE, "c1", 1_500);
        r.setPayeeName("Keells");

        assertEquals("p1", transactionService.create(r).getPayeeId());
    }

    @Test
    void create_defaultsTxnDateToNow_whenOmitted() {
        stubHappyPath(CategoryKind.EXPENSE);

        CreateTransactionRequest r = req(TransactionType.EXPENSE, "c1", 1_000);
        r.setTxnDate(null);
        long before = System.currentTimeMillis();

        TransactionResponse resp = transactionService.create(r);

        assertNotNull(resp);
        ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(saved.capture());
        // A row with no date would fall into no month at all, so it must never be 0.
        org.junit.jupiter.api.Assertions.assertTrue(saved.getValue().getTxnDate() >= before);
    }

    @Test
    void create_income_withExpenseCategory_rejected() {
        User u = user();
        when(currentUser.requireUser()).thenReturn(u);
        when(categoryRepository.findByIdAndUserId("c1", "u1"))
                .thenReturn(Optional.of(category("c1", CategoryKind.EXPENSE)));

        assertThrows(BadRequestException.class,
                () -> transactionService.create(req(TransactionType.INCOME, "c1", 1_000)));
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void create_categoryOfAnotherUser_throwsNotFound() {
        when(currentUser.requireUser()).thenReturn(user());
        when(categoryRepository.findByIdAndUserId("c1", "u1")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class,
                () -> transactionService.create(req(TransactionType.EXPENSE, "c1", 1_000)));
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void create_nonPositiveAmount_rejected() {
        when(currentUser.requireUser()).thenReturn(user());

        assertThrows(BadRequestException.class,
                () -> transactionService.create(req(TransactionType.EXPENSE, "c1", 0)));
        verify(transactionRepository, never()).save(any());
    }

    /**
     * Moving a date across a month boundary is allowed and needs no recomputation —
     * it simply changes which month sums the row (§1.10).
     */
    @Test
    void update_movesTransactionToAnotherMonth() {
        User u = user();
        Transaction txn = new Transaction();
        txn.setId("t1");
        txn.setUserId("u1");
        txn.setAccountId("a1");
        txn.setType(TransactionType.EXPENSE);
        txn.setAmount(20_000);
        txn.setTxnDate(JAN_2026);

        when(currentUser.requireUser()).thenReturn(u);
        when(accountService.requireAccountId(u)).thenReturn("a1");
        when(transactionRepository.findByIdAndUserId("t1", "u1")).thenReturn(Optional.of(txn));
        when(categoryRepository.findByIdAndUserId("c1", "u1"))
                .thenReturn(Optional.of(category("c1", CategoryKind.EXPENSE)));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateTransactionRequest r = new UpdateTransactionRequest();
        r.setType(TransactionType.EXPENSE);
        r.setCategoryId("c1");
        r.setAmount(20_000);
        r.setTxnDate(JAN_2026 + 40L * 24 * 60 * 60 * 1000);   // into February

        TransactionResponse resp = transactionService.update("t1", r);

        assertEquals(JAN_2026 + 40L * 24 * 60 * 60 * 1000, resp.getTxnDate());
    }

    @Test
    void delete_removesRow() {
        Transaction txn = new Transaction();
        txn.setId("t1");
        txn.setUserId("u1");
        txn.setAccountId("a1");
        txn.setType(TransactionType.EXPENSE);
        txn.setAmount(20_000);
        when(currentUser.requireUserId()).thenReturn("u1");
        when(transactionRepository.findByIdAndUserId("t1", "u1")).thenReturn(Optional.of(txn));

        transactionService.delete("t1");

        verify(transactionRepository).delete(txn);
    }

    // --- list filtering: account, type, date range, and combinations (F-1.9) ---

    private static final long DAY = 86_400_000L;

    private Transaction txn(String id, String accountId, TransactionType type, long amount, long date) {
        Transaction t = new Transaction();
        t.setId(id);
        t.setUserId("u1");
        t.setAccountId(accountId);
        t.setType(type);
        t.setAmount(amount);
        t.setTxnDate(date);
        return t;
    }

    private void stubLister(User u, Transaction... rows) {
        when(currentUser.requireUser()).thenReturn(u);
        when(transactionRepository.findByUserId("u1")).thenReturn(java.util.List.of(rows));
    }

    @Test
    void list_withNoFilters_returnsEverything_newestFirst() {
        stubLister(user(),
                txn("t1", "a1", TransactionType.EXPENSE, 1_000, JAN_2026),
                txn("t2", "a2", TransactionType.INCOME, 50_000, JAN_2026 + 1_000));

        var results = transactionService.list(null, null, null, null);

        assertEquals(2, results.size());
        assertEquals("t2", results.get(0).getId());   // newer first
        assertEquals("t1", results.get(1).getId());
    }

    @Test
    void list_filtersByAccount() {
        stubLister(user(),
                txn("t1", "a1", TransactionType.EXPENSE, 1_000, JAN_2026),
                txn("t2", "a2", TransactionType.EXPENSE, 2_000, JAN_2026));

        var results = transactionService.list("a2", null, null, null);

        assertEquals(1, results.size());
        assertEquals("t2", results.get(0).getId());
    }

    @Test
    void list_filtersByType() {
        stubLister(user(),
                txn("t1", "a1", TransactionType.EXPENSE, 1_000, JAN_2026),
                txn("t2", "a1", TransactionType.INCOME, 50_000, JAN_2026));

        var results = transactionService.list(null, "income", null, null);

        assertEquals(1, results.size());
        assertEquals("t2", results.get(0).getId());
    }

    @Test
    void list_unknownType_rejected() {
        when(currentUser.requireUser()).thenReturn(user());

        assertThrows(BadRequestException.class,
                () -> transactionService.list(null, "bogus", null, null));
    }

    @Test
    void list_combinesAccountTypeAndDateRange() {
        stubLister(user(),
                txn("t1", "a1", TransactionType.EXPENSE, 1_000, JAN_2026),           // wrong account
                txn("t2", "a2", TransactionType.INCOME, 50_000, JAN_2026),           // wrong type
                txn("t3", "a2", TransactionType.EXPENSE, 500, JAN_2026 - DAY),       // outside range
                txn("t4", "a2", TransactionType.EXPENSE, 2_000, JAN_2026));          // matches all

        var results = transactionService.list("a2", "expense", "2026-01-01", "2026-01-01");

        assertEquals(1, results.size());
        assertEquals("t4", results.get(0).getId());
    }

    /** endDate is an inclusive calendar date: everything up to 23:59:59.999 that day counts. */
    @Test
    void list_endDateIncludesTheWholeOfThatDay() {
        stubLister(user(),
                txn("t1", "a1", TransactionType.EXPENSE, 1_000, JAN_2026),                 // 00:00:00.000
                txn("t2", "a1", TransactionType.EXPENSE, 2_000, JAN_2026 + DAY - 1));      // 23:59:59.999

        var results = transactionService.list(null, null, "2026-01-01", "2026-01-01");

        assertEquals(2, results.size());
    }

    /**
     * The upper bound is half-open underneath (§1.10): midnight opening the day after endDate
     * belongs to the next day only — the same rule the monthly position applies, so a row on the
     * stroke of midnight can never be counted by both or neither.
     */
    @Test
    void list_excludesMidnightOpeningTheDayAfterEndDate() {
        stubLister(user(),
                txn("t1", "a1", TransactionType.EXPENSE, 1_000, JAN_2026 + DAY - 1),   // 01 Jan 23:59:59.999
                txn("t2", "a1", TransactionType.EXPENSE, 2_000, JAN_2026 + DAY));      // 02 Jan 00:00:00.000

        var results = transactionService.list(null, null, "2026-01-01", "2026-01-01");

        assertEquals(1, results.size());
        assertEquals("t1", results.get(0).getId());
    }

    /**
     * The dates are resolved in the caller's zone, not UTC. In {@code Asia/Colombo} (UTC+5:30)
     * 2026-01-01 starts at 2025-12-31T18:30Z, so the two rows either side of that instant fall
     * on different calendar days for this user than they would for a UTC one.
     */
    @Test
    void list_resolvesDatesInTheCallersTimezone() {
        long colomboMidnight = JAN_2026 - (5 * 60 + 30) * 60_000L;   // 2025-12-31T18:30Z
        stubLister(user("Asia/Colombo"),
                txn("t1", "a1", TransactionType.EXPENSE, 1_000, colomboMidnight - 1),   // 31 Dec, Colombo
                txn("t2", "a1", TransactionType.EXPENSE, 2_000, colomboMidnight));      // 01 Jan, Colombo

        var results = transactionService.list(null, null, "2026-01-01", "2026-01-01");

        assertEquals(1, results.size());
        assertEquals("t2", results.get(0).getId());
    }

    @Test
    void list_badDateFormat_rejected() {
        when(currentUser.requireUser()).thenReturn(user());

        assertThrows(BadRequestException.class,
                () -> transactionService.list(null, null, "01/01/2026", null));
    }

    @Test
    void list_startDateAfterEndDate_rejected() {
        when(currentUser.requireUser()).thenReturn(user());

        assertThrows(BadRequestException.class,
                () -> transactionService.list(null, null, "2026-01-31", "2026-01-01"));
    }

    @Test
    void get_notOwned_throwsNotFound() {
        when(currentUser.requireUserId()).thenReturn("u1");
        when(transactionRepository.findByIdAndUserId("x", "u1")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> transactionService.get("x"));
    }

    /** A generated row copies the template verbatim and is stamped with its run date. */
    @Test
    void generateFromRecurring_copiesTemplateOntoRunDate() {
        RecurringTransaction template = new RecurringTransaction();
        template.setId("r1");
        template.setUserId("u1");
        template.setAccountId("a1");
        template.setType(TransactionType.EXPENSE);
        template.setCategoryId("c1");
        template.setAmount(9_900);
        template.setCurrency("USD");
        template.setPayeeId("p1");
        template.setNote("Netflix");
        when(transactionRepository.save(any())).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            t.setId("t1");
            return t;
        });

        transactionService.generateFromRecurring(template, JAN_2026);

        ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(saved.capture());
        Transaction t = saved.getValue();
        assertEquals("a1", t.getAccountId());
        assertEquals("c1", t.getCategoryId());
        assertEquals(9_900, t.getAmount());
        assertEquals("p1", t.getPayeeId());
        assertEquals("r1", t.getRecurringId());
        assertEquals(JAN_2026, t.getTxnDate());
    }
}
