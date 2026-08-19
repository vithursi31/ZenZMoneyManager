package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.AccountStatus;
import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.common.exception.NotFoundException;
import com.zenzmoney.core.entity.Account;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.AccountRepository;
import com.zenzmoney.core.repository.TransactionRepository;
import com.zenzmoney.core.web.dto.AccountResponse;
import com.zenzmoney.core.web.dto.CreateAccountRequest;
import com.zenzmoney.core.web.dto.UpdateAccountNameRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        when(accountRepository.findFirstByUserIdAndStatusOrderByCreatedTimeAsc("u1", AccountStatus.ACTIVE))
                .thenReturn(Optional.of(existing()));

        AccountResponse resp = accountService.current();

        assertEquals("a1", resp.getId());
        assertEquals("USD", resp.getCurrency());
        verify(accountRepository, never()).saveAndFlush(any());
    }

    @Test
    void provision_createsOnce_inTheUsersActiveCurrency() {
        User u = user("LKR");
        when(accountRepository.findFirstByUserIdAndStatusOrderByCreatedTimeAsc("u1", AccountStatus.ACTIVE))
                .thenReturn(Optional.empty());
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
        when(accountRepository.findFirstByUserIdAndStatusOrderByCreatedTimeAsc("u1", AccountStatus.ACTIVE))
                .thenReturn(Optional.of(existing()));

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
        when(accountRepository.findFirstByUserIdAndStatusOrderByCreatedTimeAsc("u1", AccountStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThrows(BadRequestException.class, () -> accountService.provision(user(null)));
        verify(accountRepository, never()).saveAndFlush(any());
    }

    /**
     * Two concurrent first-writes both miss the read and both insert. Re-reading on
     * a constraint violation must resolve to the winner's account rather than fail,
     * for the (now largely historical) case where something still constrains a
     * user to one row.
     */
    @Test
    void provision_losingTheInsertRace_returnsTheWinnersAccount() {
        Account winner = existing();
        when(accountRepository.findFirstByUserIdAndStatusOrderByCreatedTimeAsc("u1", AccountStatus.ACTIVE))
                .thenReturn(Optional.empty())      // our read, before the race
                .thenReturn(Optional.of(winner));  // re-read after the constraint fires
        when(accountRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertEquals("a1", accountService.provision(user("USD")).getId());
    }

    @Test
    void requireAccountId_returnsTheId_everyLedgerWriteUses() {
        User u = user("USD");
        when(accountRepository.findFirstByUserIdAndStatusOrderByCreatedTimeAsc("u1", AccountStatus.ACTIVE))
                .thenReturn(Optional.of(existing()));

        assertEquals("a1", accountService.requireAccountId(u));
    }

    // --- creation & rename: adding beyond the primary account (F-F.1) ---

    private CreateAccountRequest createReq(String name) {
        CreateAccountRequest req = new CreateAccountRequest();
        req.setName(name);
        return req;
    }

    private UpdateAccountNameRequest renameReq(String name) {
        UpdateAccountNameRequest req = new UpdateAccountNameRequest();
        req.setName(name);
        return req;
    }

    @Test
    void create_addsANamedAccount_inTheUsersActiveCurrency() {
        when(currentUser.requireUser()).thenReturn(user("USD"));
        when(accountRepository.saveAndFlush(any())).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setId("a-new");
            return a;
        });

        AccountResponse resp = accountService.create(createReq("Savings"));

        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).saveAndFlush(saved.capture());
        assertEquals("Savings", saved.getValue().getName());
        assertEquals("USD", saved.getValue().getCurrency());
        assertEquals("Savings", resp.getName());
        assertEquals("a-new", resp.getId());
    }

    @Test
    void create_normalizesABlankName_toUnnamed() {
        when(currentUser.requireUser()).thenReturn(user("USD"));
        when(accountRepository.saveAndFlush(any())).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setId("a-new");
            return a;
        });

        accountService.create(createReq("   "));

        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).saveAndFlush(saved.capture());
        assertNull(saved.getValue().getName());
    }

    @Test
    void create_withoutActiveCurrency_rejected() {
        when(currentUser.requireUser()).thenReturn(user(null));

        assertThrows(BadRequestException.class, () -> accountService.create(createReq("Savings")));
        verify(accountRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateName_renamesAnOwnedAccount() {
        Account account = existing();
        when(currentUser.requireUserId()).thenReturn("u1");
        when(accountRepository.findByIdAndUserId("a1", "u1")).thenReturn(Optional.of(account));

        AccountResponse resp = accountService.updateName("a1", renameReq("Rent"));

        assertEquals("Rent", account.getName());
        assertEquals("Rent", resp.getName());
        verify(accountRepository).save(account);
    }

    @Test
    void updateName_throwsNotFound_whenNotOwnedByCaller() {
        when(currentUser.requireUserId()).thenReturn("u1");
        when(accountRepository.findByIdAndUserId("someone-elses", "u1")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> accountService.updateName("someone-elses", renameReq("Rent")));
    }

    @Test
    void updateName_refused_onADeletedAccount() {
        Account deleted = existing();
        deleted.setStatus(AccountStatus.DELETED);
        when(currentUser.requireUserId()).thenReturn("u1");
        when(accountRepository.findByIdAndUserId("a1", "u1")).thenReturn(Optional.of(deleted));

        assertThrows(BadRequestException.class, () -> accountService.updateName("a1", renameReq("Rent")));
        verify(accountRepository, never()).save(any());
    }

    // --- listing & delete: multiple accounts per user (F-F.1) ---

    @Test
    void listActive_returnsOnlyActiveAccounts() {
        Account active = existing();
        when(currentUser.requireUserId()).thenReturn("u1");
        when(accountRepository.findByUserIdAndStatus("u1", AccountStatus.ACTIVE))
                .thenReturn(List.of(active));

        List<AccountResponse> accounts = accountService.listActive();

        assertEquals(1, accounts.size());
        assertEquals("a1", accounts.get(0).getId());
    }

    @Test
    void findOne_returnsAnOwnedAccount() {
        when(currentUser.requireUserId()).thenReturn("u1");
        when(accountRepository.findByIdAndUserId("a1", "u1")).thenReturn(Optional.of(existing()));

        AccountResponse resp = accountService.findOne("a1");

        assertEquals("a1", resp.getId());
        assertEquals("USD", resp.getCurrency());
    }

    @Test
    void findOne_throwsNotFound_whenNotOwnedByCaller() {
        when(currentUser.requireUserId()).thenReturn("u1");
        when(accountRepository.findByIdAndUserId("someone-elses", "u1")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> accountService.findOne("someone-elses"));
    }

    @Test
    void delete_marksStatusDeleted_whenAnotherActiveAccountRemains() {
        Account toDelete = existing();
        when(currentUser.requireUserId()).thenReturn("u1");
        when(accountRepository.findByIdAndUserId("a1", "u1")).thenReturn(Optional.of(toDelete));
        when(accountRepository.countByUserIdAndStatus("u1", AccountStatus.ACTIVE)).thenReturn(2L);

        accountService.delete("a1");

        assertEquals(AccountStatus.DELETED, toDelete.getStatus());
        verify(accountRepository).save(toDelete);
    }

    @Test
    void delete_refused_whenItIsTheLastActiveAccount() {
        Account onlyAccount = existing();
        when(currentUser.requireUserId()).thenReturn("u1");
        when(accountRepository.findByIdAndUserId("a1", "u1")).thenReturn(Optional.of(onlyAccount));
        when(accountRepository.countByUserIdAndStatus("u1", AccountStatus.ACTIVE)).thenReturn(1L);

        assertThrows(BadRequestException.class, () -> accountService.delete("a1"));
        assertEquals(AccountStatus.ACTIVE, onlyAccount.getStatus());
        verify(accountRepository, never()).save(any());
    }

    @Test
    void delete_allowed_forAnInactiveAccount_evenAsTheUsersOnlyAccount() {
        Account inactive = existing();
        inactive.setStatus(AccountStatus.INACTIVE);
        when(currentUser.requireUserId()).thenReturn("u1");
        when(accountRepository.findByIdAndUserId("a1", "u1")).thenReturn(Optional.of(inactive));

        accountService.delete("a1");

        assertEquals(AccountStatus.DELETED, inactive.getStatus());
        verify(accountRepository, never()).countByUserIdAndStatus(any(), any());
    }

    @Test
    void delete_refused_whenAlreadyDeleted() {
        Account deleted = existing();
        deleted.setStatus(AccountStatus.DELETED);
        when(currentUser.requireUserId()).thenReturn("u1");
        when(accountRepository.findByIdAndUserId("a1", "u1")).thenReturn(Optional.of(deleted));

        assertThrows(BadRequestException.class, () -> accountService.delete("a1"));
        verify(accountRepository, never()).save(any());
    }

    @Test
    void delete_throwsNotFound_whenNotOwnedByCaller() {
        when(currentUser.requireUserId()).thenReturn("u1");
        when(accountRepository.findByIdAndUserId("someone-elses", "u1")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> accountService.delete("someone-elses"));
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
        when(accountRepository.findFirstByUserIdAndStatusOrderByCreatedTimeAsc("u1", AccountStatus.ACTIVE))
                .thenReturn(Optional.of(account));
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
        when(accountRepository.findFirstByUserIdAndStatusOrderByCreatedTimeAsc("u1", AccountStatus.ACTIVE))
                .thenReturn(Optional.of(existing()));
        when(transactionRepository.existsByUserId("u1")).thenReturn(true);

        assertThrows(BadRequestException.class, () -> accountService.redenominate(user("USD"), "LKR"));
        verify(accountRepository, never()).save(any());
    }

    /** The common path: onboarding runs before the app ever reads /account. */
    @Test
    void redenominate_isANoOp_whenNoAccountExistsYet() {
        when(accountRepository.findFirstByUserIdAndStatusOrderByCreatedTimeAsc("u1", AccountStatus.ACTIVE))
                .thenReturn(Optional.empty());

        accountService.redenominate(user(null), "LKR");

        verify(accountRepository, never()).save(any());
    }

    /** Confirming the guessed currency must not count as a change, even with amounts recorded. */
    @Test
    void redenominate_isANoOp_whenTheCurrencyAlreadyMatches() {
        when(accountRepository.findFirstByUserIdAndStatusOrderByCreatedTimeAsc("u1", AccountStatus.ACTIVE))
                .thenReturn(Optional.of(existing()));

        accountService.redenominate(user("USD"), "USD");

        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).existsByUserId(any());
    }
}
