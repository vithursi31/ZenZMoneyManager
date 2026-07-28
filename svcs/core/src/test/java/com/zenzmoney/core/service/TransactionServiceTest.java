package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.AccountStatus;
import com.zenzmoney.common.domain.CategoryKind;
import com.zenzmoney.common.domain.TransactionType;
import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.common.exception.NotFoundException;
import com.zenzmoney.core.entity.Account;
import com.zenzmoney.core.entity.Category;
import com.zenzmoney.core.entity.Transaction;
import com.zenzmoney.core.repository.AccountRepository;
import com.zenzmoney.core.repository.CategoryRepository;
import com.zenzmoney.core.repository.TransactionRepository;
import com.zenzmoney.core.web.dto.CreateTransactionRequest;
import com.zenzmoney.core.web.dto.TransactionResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock TransactionRepository transactionRepository;
    @Mock AccountRepository accountRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock PayeeService payeeService;
    @Mock CurrentUserService currentUser;
    @InjectMocks TransactionService transactionService;

    private Account account(String id, long opening, AccountStatus status) {
        Account a = new Account();
        a.setId(id);
        a.setUserId("u1");
        a.setCurrency("USD");
        a.setStatus(status);
        a.setOpeningBalance(opening);
        a.setCurrentBalance(opening);
        return a;
    }

    private Category category(String id, CategoryKind kind) {
        Category c = new Category();
        c.setId(id);
        c.setUserId("u1");
        c.setKind(kind);
        return c;
    }

    private CreateTransactionRequest req(TransactionType type, String accountId, String categoryId,
                                         long amount, String transferAccountId) {
        CreateTransactionRequest r = new CreateTransactionRequest();
        r.setType(type);
        r.setAccountId(accountId);
        r.setCategoryId(categoryId);
        r.setAmount(amount);
        r.setTransferAccountId(transferAccountId);
        r.setTxnDate(1_700_000_000_000L);
        return r;
    }

    private void stubRecompute(String accountId, long income, long expense, long transferOut, long transferIn) {
        when(transactionRepository.sumAmountByAccountIdAndType(accountId, TransactionType.INCOME)).thenReturn(income);
        when(transactionRepository.sumAmountByAccountIdAndType(accountId, TransactionType.EXPENSE)).thenReturn(expense);
        when(transactionRepository.sumAmountByAccountIdAndType(accountId, TransactionType.TRANSFER)).thenReturn(transferOut);
        when(transactionRepository.sumTransferInByAccountId(accountId)).thenReturn(transferIn);
    }

    @Test
    void create_income_addsToBalance() {
        Account acc = account("a1", 100_000, AccountStatus.ACTIVE);
        when(currentUser.requireUserId()).thenReturn("u1");
        when(accountRepository.findByIdAndUserId("a1", "u1")).thenReturn(Optional.of(acc));
        when(categoryRepository.findByIdAndUserId("c1", "u1")).thenReturn(Optional.of(category("c1", CategoryKind.INCOME)));
        when(transactionRepository.save(any())).thenAnswer(inv -> { Transaction t = inv.getArgument(0); t.setId("t1"); return t; });
        when(accountRepository.findById("a1")).thenReturn(Optional.of(acc));
        stubRecompute("a1", 50_000, 0, 0, 0);
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TransactionResponse resp = transactionService.create(req(TransactionType.INCOME, "a1", "c1", 50_000, null));

        assertEquals(TransactionType.INCOME, resp.getType());
        assertEquals("USD", resp.getCurrency());
        assertEquals(150_000, acc.getCurrentBalance());   // opening 100000 + income 50000
    }

    @Test
    void create_expense_subtractsFromBalance() {
        Account acc = account("a1", 100_000, AccountStatus.ACTIVE);
        when(currentUser.requireUserId()).thenReturn("u1");
        when(accountRepository.findByIdAndUserId("a1", "u1")).thenReturn(Optional.of(acc));
        when(categoryRepository.findByIdAndUserId("c1", "u1")).thenReturn(Optional.of(category("c1", CategoryKind.EXPENSE)));
        when(transactionRepository.save(any())).thenAnswer(inv -> { Transaction t = inv.getArgument(0); t.setId("t1"); return t; });
        when(accountRepository.findById("a1")).thenReturn(Optional.of(acc));
        stubRecompute("a1", 0, 20_000, 0, 0);
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        transactionService.create(req(TransactionType.EXPENSE, "a1", "c1", 20_000, null));

        assertEquals(80_000, acc.getCurrentBalance());   // 100000 - 20000
    }

    @Test
    void create_transfer_movesBetweenBothAccounts() {
        Account src = account("a1", 100_000, AccountStatus.ACTIVE);
        Account dst = account("a2", 0, AccountStatus.ACTIVE);
        when(currentUser.requireUserId()).thenReturn("u1");
        when(accountRepository.findByIdAndUserId("a1", "u1")).thenReturn(Optional.of(src));
        when(accountRepository.findByIdAndUserId("a2", "u1")).thenReturn(Optional.of(dst));
        when(transactionRepository.save(any())).thenAnswer(inv -> { Transaction t = inv.getArgument(0); t.setId("t1"); return t; });
        when(accountRepository.findById("a1")).thenReturn(Optional.of(src));
        when(accountRepository.findById("a2")).thenReturn(Optional.of(dst));
        stubRecompute("a1", 0, 0, 30_000, 0);   // 30000 out of source
        stubRecompute("a2", 0, 0, 0, 30_000);   // 30000 into dest
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        transactionService.create(req(TransactionType.TRANSFER, "a1", null, 30_000, "a2"));

        assertEquals(70_000, src.getCurrentBalance());   // 100000 - 30000
        assertEquals(30_000, dst.getCurrentBalance());   // 0 + 30000
    }

    @Test
    void create_resolvesPayee_ontoTransaction() {
        Account acc = account("a1", 0, AccountStatus.ACTIVE);
        when(currentUser.requireUserId()).thenReturn("u1");
        when(accountRepository.findByIdAndUserId("a1", "u1")).thenReturn(Optional.of(acc));
        when(categoryRepository.findByIdAndUserId("c1", "u1")).thenReturn(Optional.of(category("c1", CategoryKind.EXPENSE)));
        when(payeeService.resolveOrCreate(eq("u1"), eq("Keells"))).thenReturn("p1");
        when(transactionRepository.save(any())).thenAnswer(inv -> { Transaction t = inv.getArgument(0); t.setId("t1"); return t; });
        when(accountRepository.findById("a1")).thenReturn(Optional.of(acc));
        stubRecompute("a1", 0, 1_500, 0, 0);
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateTransactionRequest r = req(TransactionType.EXPENSE, "a1", "c1", 1_500, null);
        r.setPayeeName("Keells");
        TransactionResponse resp = transactionService.create(r);

        assertEquals("p1", resp.getPayeeId());
    }

    @Test
    void create_expense_withoutCategory_rejected() {
        when(currentUser.requireUserId()).thenReturn("u1");
        when(accountRepository.findByIdAndUserId("a1", "u1")).thenReturn(Optional.of(account("a1", 0, AccountStatus.ACTIVE)));

        assertThrows(BadRequestException.class,
                () -> transactionService.create(req(TransactionType.EXPENSE, "a1", null, 1_000, null)));
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void create_income_withExpenseCategory_rejected() {
        when(currentUser.requireUserId()).thenReturn("u1");
        when(accountRepository.findByIdAndUserId("a1", "u1")).thenReturn(Optional.of(account("a1", 0, AccountStatus.ACTIVE)));
        when(categoryRepository.findByIdAndUserId("c1", "u1")).thenReturn(Optional.of(category("c1", CategoryKind.EXPENSE)));

        assertThrows(BadRequestException.class,
                () -> transactionService.create(req(TransactionType.INCOME, "a1", "c1", 1_000, null)));
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void create_transfer_withoutDestination_rejected() {
        when(currentUser.requireUserId()).thenReturn("u1");
        when(accountRepository.findByIdAndUserId("a1", "u1")).thenReturn(Optional.of(account("a1", 0, AccountStatus.ACTIVE)));

        assertThrows(BadRequestException.class,
                () -> transactionService.create(req(TransactionType.TRANSFER, "a1", null, 1_000, null)));
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void create_transfer_toSameAccount_rejected() {
        when(currentUser.requireUserId()).thenReturn("u1");
        when(accountRepository.findByIdAndUserId("a1", "u1")).thenReturn(Optional.of(account("a1", 0, AccountStatus.ACTIVE)));

        assertThrows(BadRequestException.class,
                () -> transactionService.create(req(TransactionType.TRANSFER, "a1", null, 1_000, "a1")));
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void create_transfer_withCategory_rejected() {
        when(currentUser.requireUserId()).thenReturn("u1");
        when(accountRepository.findByIdAndUserId("a1", "u1")).thenReturn(Optional.of(account("a1", 0, AccountStatus.ACTIVE)));

        assertThrows(BadRequestException.class,
                () -> transactionService.create(req(TransactionType.TRANSFER, "a1", "c1", 1_000, "a2")));
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void create_onArchivedAccount_rejected() {
        when(currentUser.requireUserId()).thenReturn("u1");
        when(accountRepository.findByIdAndUserId("a1", "u1")).thenReturn(Optional.of(account("a1", 0, AccountStatus.ARCHIVED)));

        assertThrows(BadRequestException.class,
                () -> transactionService.create(req(TransactionType.EXPENSE, "a1", "c1", 1_000, null)));
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void create_nonPositiveAmount_rejected() {
        when(currentUser.requireUserId()).thenReturn("u1");

        assertThrows(BadRequestException.class,
                () -> transactionService.create(req(TransactionType.EXPENSE, "a1", "c1", 0, null)));
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void delete_recomputesAffectedAccount() {
        Account acc = account("a1", 100_000, AccountStatus.ACTIVE);
        Transaction txn = new Transaction();
        txn.setId("t1");
        txn.setUserId("u1");
        txn.setAccountId("a1");
        txn.setType(TransactionType.EXPENSE);
        txn.setAmount(20_000);
        when(currentUser.requireUserId()).thenReturn("u1");
        when(transactionRepository.findByIdAndUserId("t1", "u1")).thenReturn(Optional.of(txn));
        when(accountRepository.findById("a1")).thenReturn(Optional.of(acc));
        stubRecompute("a1", 0, 0, 0, 0);   // after delete, no expenses left
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        transactionService.delete("t1");

        verify(transactionRepository).delete(txn);
        assertEquals(100_000, acc.getCurrentBalance());   // back to opening
    }

    @Test
    void get_notOwned_throwsNotFound() {
        when(currentUser.requireUserId()).thenReturn("u1");
        when(transactionRepository.findByIdAndUserId("x", "u1")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> transactionService.get("x"));
    }
}
