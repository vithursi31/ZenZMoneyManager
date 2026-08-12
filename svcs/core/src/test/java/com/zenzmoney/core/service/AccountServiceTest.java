package com.zenzmoney.core.service;

import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.core.entity.Account;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.AccountRepository;
import com.zenzmoney.core.repository.TransactionRepository;
import com.zenzmoney.core.web.dto.AccountResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock AccountRepository accountRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock CurrentUserService currentUser;
    @InjectMocks AccountService accountService;

    private User user(String currency) {
        User u = new User();
        u.setId("u1");
        u.setActiveCurrency(currency);
        return u;
    }

    private Account existing() {
        Account a = new Account();
        a.setId("a1");
        a.setUserId("u1");
        a.setCurrency("USD");
        return a;
    }

    @Test
    void current_returnsTheExistingAccount_withoutCreatingAnother() {
        when(currentUser.requireUser()).thenReturn(user("USD"));
        when(accountRepository.findByUserId("u1")).thenReturn(Optional.of(existing()));

        AccountResponse resp = accountService.current();

        assertEquals("a1", resp.getId());
        assertEquals("USD", resp.getCurrency());
        verify(accountRepository, never()).saveAndFlush(any());
    }

    @Test
    void provision_createsOnce_inTheUsersActiveCurrency() {
        User u = user("LKR");
        when(accountRepository.findByUserId("u1")).thenReturn(Optional.empty());
        when(accountRepository.saveAndFlush(any())).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setId("a-new");
            return a;
        });

        Account created = accountService.provision(u);

        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).saveAndFlush(saved.capture());
        assertEquals("u1", saved.getValue().getUserId());
        assertEquals("LKR", saved.getValue().getCurrency());
        assertEquals("a-new", created.getId());
    }

    @Test
    void provision_isIdempotent_whenAnAccountAlreadyExists() {
        when(accountRepository.findByUserId("u1")).thenReturn(Optional.of(existing()));

        assertEquals("a1", accountService.provision(user("USD")).getId());
        verify(accountRepository, never()).saveAndFlush(any());
    }

    /**
     * The account is denominated in the active currency, so it cannot be created
     * before onboarding picks one — otherwise the first transaction would silently
     * invent a denomination.
     */
    @Test
    void provision_withoutActiveCurrency_rejected() {
        when(accountRepository.findByUserId("u1")).thenReturn(Optional.empty());

        assertThrows(BadRequestException.class, () -> accountService.provision(user(null)));
        verify(accountRepository, never()).saveAndFlush(any());
    }

    /**
     * Two concurrent first-writes both miss the read and both insert. The unique index
     * rejects the loser, and it must resolve to the winner's account rather than fail —
     * a user must never end up with two accounts, nor a 500 on their first expense.
     */
    @Test
    void provision_losingTheInsertRace_returnsTheWinnersAccount() {
        Account winner = existing();
        when(accountRepository.findByUserId("u1"))
                .thenReturn(Optional.empty())      // our read, before the race
                .thenReturn(Optional.of(winner));  // re-read after the constraint fires
        when(accountRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertEquals("a1", accountService.provision(user("USD")).getId());
    }

    @Test
    void requireAccountId_returnsTheId_everyLedgerWriteUses() {
        User u = user("USD");
        when(accountRepository.findByUserId("u1")).thenReturn(Optional.of(existing()));

        assertEquals("a1", accountService.requireAccountId(u));
    }

    // --- re-denomination: correcting a signup guess before onboarding (F-1.27) ---

    /**
     * The account is provisioned lazily, so it can already exist in the guessed
     * currency by the time the user reaches onboarding and picks a different one.
     * Nothing is recorded yet, so the column is free to move.
     */
    @Test
    void redenominate_movesTheAccount_whileTheLedgerIsEmpty() {
        Account account = existing();   // USD
        when(accountRepository.findByUserId("u1")).thenReturn(Optional.of(account));
        when(transactionRepository.existsByUserId("u1")).thenReturn(false);

        accountService.redenominate(user("USD"), "LKR");

        assertEquals("LKR", account.getCurrency());
        verify(accountRepository).save(account);
    }

    /**
     * A stored amount is a bare minor-unit integer; its denomination lives only in
     * this column. Moving it would reinterpret 1000 LKR as 1000 USD (§0.3).
     */
    @Test
    void redenominate_refused_onceAmountsExist() {
        when(accountRepository.findByUserId("u1")).thenReturn(Optional.of(existing()));
        when(transactionRepository.existsByUserId("u1")).thenReturn(true);

        assertThrows(BadRequestException.class, () -> accountService.redenominate(user("USD"), "LKR"));
        verify(accountRepository, never()).save(any());
    }

    /** The common path: onboarding runs before the app ever reads /account. */
    @Test
    void redenominate_isANoOp_whenNoAccountExistsYet() {
        when(accountRepository.findByUserId("u1")).thenReturn(Optional.empty());

        accountService.redenominate(user(null), "LKR");

        verify(accountRepository, never()).save(any());
    }

    /** Confirming the guessed currency must not count as a change, even with amounts recorded. */
    @Test
    void redenominate_isANoOp_whenTheCurrencyAlreadyMatches() {
        when(accountRepository.findByUserId("u1")).thenReturn(Optional.of(existing()));

        accountService.redenominate(user("USD"), "USD");

        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).existsByUserId(any());
    }
}
