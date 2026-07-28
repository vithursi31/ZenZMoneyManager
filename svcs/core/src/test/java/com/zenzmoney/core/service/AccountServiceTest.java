package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.AccountStatus;
import com.zenzmoney.common.domain.AccountType;
import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.common.exception.NotFoundException;
import com.zenzmoney.core.entity.Account;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.AccountRepository;
import com.zenzmoney.core.repository.TransactionRepository;
import com.zenzmoney.core.web.dto.AccountResponse;
import com.zenzmoney.core.web.dto.CreateAccountRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    private User user(String id, String activeCurrency) {
        User u = new User();
        u.setId(id);
        u.setActiveCurrency(activeCurrency);
        return u;
    }

    private Account owned(String id, String userId) {
        Account a = new Account();
        a.setId(id);
        a.setUserId(userId);
        a.setName("Bank");
        a.setType(AccountType.BANK);
        a.setCurrency("USD");
        a.setStatus(AccountStatus.ACTIVE);
        return a;
    }

    private CreateAccountRequest createReq(String currency, long opening) {
        CreateAccountRequest r = new CreateAccountRequest();
        r.setName("Chase Checking");
        r.setType(AccountType.BANK);
        r.setCurrency(currency);
        r.setOpeningBalance(opening);
        return r;
    }

    @Test
    void create_usesUserActiveCurrency_andSeedsCurrentBalanceFromOpening() {
        when(currentUser.requireUser()).thenReturn(user("u1", "USD"));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // request currency "EUR" must be ignored in favour of the user's active "USD"
        AccountResponse resp = accountService.create(createReq("EUR", 500_000));

        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(saved.capture());
        assertEquals("u1", saved.getValue().getUserId());
        assertEquals("USD", saved.getValue().getCurrency());
        assertEquals(500_000, saved.getValue().getCurrentBalance());   // == opening, no ledger yet
        assertEquals(500_000, saved.getValue().getOpeningBalance());
        assertEquals(AccountStatus.ACTIVE, saved.getValue().getStatus());
        assertEquals("USD", resp.getCurrency());
    }

    @Test
    void create_fallsBackToRequestCurrency_whenUserHasNoActiveCurrency() {
        when(currentUser.requireUser()).thenReturn(user("u1", null));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AccountResponse resp = accountService.create(createReq("eur", 0));

        assertEquals("EUR", resp.getCurrency());   // normalised to upper-case
    }

    @Test
    void create_rejects_whenNoCurrencyAnywhere() {
        when(currentUser.requireUser()).thenReturn(user("u1", null));

        assertThrows(BadRequestException.class, () -> accountService.create(createReq(null, 0)));
        verify(accountRepository, never()).save(any());
    }

    @Test
    void delete_blocked_whenAccountHasTransactions() {
        when(currentUser.requireUserId()).thenReturn("u1");
        when(accountRepository.findByIdAndUserId("a1", "u1")).thenReturn(Optional.of(owned("a1", "u1")));
        when(transactionRepository.existsByAccountId("a1")).thenReturn(true);

        assertThrows(BadRequestException.class, () -> accountService.delete("a1"));
        verify(accountRepository, never()).delete(any());
    }

    @Test
    void delete_softDeletes_whenNoTransactions() {
        Account a = owned("a1", "u1");
        when(currentUser.requireUserId()).thenReturn("u1");
        when(accountRepository.findByIdAndUserId("a1", "u1")).thenReturn(Optional.of(a));
        when(transactionRepository.existsByAccountId("a1")).thenReturn(false);
        when(transactionRepository.existsByTransferAccountId("a1")).thenReturn(false);
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        accountService.delete("a1");

        assertEquals(AccountStatus.DELETED, a.getStatus());   // soft delete
        verify(accountRepository).save(a);
        verify(accountRepository, never()).delete(any());     // row is kept
    }

    @Test
    void softDeletedAccount_readsAsNotFound() {
        Account a = owned("a1", "u1");
        a.setStatus(AccountStatus.DELETED);
        when(currentUser.requireUserId()).thenReturn("u1");
        when(accountRepository.findByIdAndUserId("a1", "u1")).thenReturn(Optional.of(a));

        assertThrows(NotFoundException.class, () -> accountService.get("a1"));
    }

    @Test
    void get_notOwned_throwsNotFound() {
        when(currentUser.requireUserId()).thenReturn("u1");
        when(accountRepository.findByIdAndUserId("x", "u1")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> accountService.get("x"));
    }

    @Test
    void archive_setsStatusArchived() {
        Account a = owned("a1", "u1");
        when(currentUser.requireUserId()).thenReturn("u1");
        when(accountRepository.findByIdAndUserId("a1", "u1")).thenReturn(Optional.of(a));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AccountResponse resp = accountService.archive("a1");

        assertEquals(AccountStatus.ARCHIVED, resp.getStatus());
    }
}
