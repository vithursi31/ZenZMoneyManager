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
import com.zenzmoney.core.web.dto.UpdateAccountRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * CRUD for {@link Account}, scoped to the authenticated user (§1.4).
 * A new account's currency mirrors the user's active currency (§0.3);
 * {@code currentBalance} starts at the opening balance and is thereafter
 * maintained by the ledger (§1.10) — not set here after creation.
 */
@Service
public class AccountService {

    /** Mutations only. Reads are already covered by the per-request line MdcContextFilter writes. */
    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final CurrentUserService currentUser;

    public AccountService(AccountRepository accountRepository,
                          TransactionRepository transactionRepository,
                          CurrentUserService currentUser) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.currentUser = currentUser;
    }

    @Transactional
    public AccountResponse create(CreateAccountRequest req) {
        User user = currentUser.requireUser();

        Account account = new Account();
        account.setUserId(user.getId());
        account.setName(req.getName().trim());
        account.setType(req.getType());
        account.setCurrency(resolveCurrency(user, req.getCurrency()));
        account.setOpeningBalance(req.getOpeningBalance());
        account.setCurrentBalance(req.getOpeningBalance());   // no ledger entries yet
        account.setColor(req.getColor());
        account.setIcon(req.getIcon());
        account.setSortOrder(req.getSortOrder());
        account.setStatus(AccountStatus.ACTIVE);

        Account saved = accountRepository.save(account);
        log.info("Account created: {} type={} currency={} opening={} (account {}, user {})",
                saved.getName(), saved.getType(), saved.getCurrency(),
                saved.getOpeningBalance(), saved.getId(), user.getId());
        return AccountResponse.of(saved);
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> list(boolean includeArchived) {
        String userId = currentUser.requireUserId();
        return accountRepository.findByUserId(userId).stream()
                .filter(a -> a.getStatus() != AccountStatus.DELETED)   // soft-deleted are never listed
                .filter(a -> includeArchived || a.getStatus() != AccountStatus.ARCHIVED)
                .sorted(Comparator.comparingInt(Account::getSortOrder)
                        .thenComparing(Account::getName))
                .map(AccountResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public AccountResponse get(String id) {
        return AccountResponse.of(requireOwned(id));
    }

    @Transactional
    public AccountResponse update(String id, UpdateAccountRequest req) {
        Account account = requireOwned(id);
        if (req.getName() != null && !req.getName().isBlank()) {
            account.setName(req.getName().trim());
        }
        if (req.getColor() != null) account.setColor(req.getColor());
        if (req.getIcon() != null) account.setIcon(req.getIcon());
        if (req.getSortOrder() != null) account.setSortOrder(req.getSortOrder());
        log.info("Account updated: {} (account {}, user {})",
                account.getName(), id, account.getUserId());
        return AccountResponse.of(accountRepository.save(account));
    }

    @Transactional
    public AccountResponse archive(String id) {
        Account account = requireOwned(id);
        account.setStatus(AccountStatus.ARCHIVED);
        log.info("Account archived: {} (account {}, user {}, balance {} {})",
                account.getName(), id, account.getUserId(),
                account.getCurrentBalance(), account.getCurrency());
        return AccountResponse.of(accountRepository.save(account));
    }

    /**
     * Soft-deletes an account: its {@code status} is set to {@link AccountStatus#DELETED}
     * and the row is <b>kept</b> in the database (audit / recovery); it is then hidden
     * from all listings and operations. Allowed only when the account has no ledger
     * history; an account with transactions must be archived instead (§1.4).
     */
    @Transactional
    public void delete(String id) {
        Account account = requireOwned(id);
        if (transactionRepository.existsByAccountId(id)
                || transactionRepository.existsByTransferAccountId(id)) {
            throw new BadRequestException(
                    "Account has transactions and cannot be deleted; archive it instead.");
        }
        account.setStatus(AccountStatus.DELETED);
        accountRepository.save(account);
        log.info("Account soft-deleted: {} (account {}, user {})",
                account.getName(), id, account.getUserId());
    }

    /** Owned, non-deleted account; a soft-deleted account reads as not found. */
    private Account requireOwned(String id) {
        String userId = currentUser.requireUserId();
        Account account = accountRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new NotFoundException("Account not found"));
        if (account.getStatus() == AccountStatus.DELETED) {
            throw new NotFoundException("Account not found");
        }
        return account;
    }

    /**
     * MVP single-currency rule (§0.3): if the user already operates in a currency,
     * the account uses it; otherwise the request must supply one (ISO-4217).
     */
    private String resolveCurrency(User user, String requested) {
        String active = user.getActiveCurrency();
        if (active != null && !active.isBlank()) {
            return active.toUpperCase();
        }
        if (requested != null && !requested.isBlank()) {
            return requested.toUpperCase();
        }
        throw new BadRequestException(
                "No active currency set; provide a currency for the first account.");
    }
}
