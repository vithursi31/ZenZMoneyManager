package com.zenzmoney.core.service;

import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.core.entity.Account;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.AccountRepository;
import com.zenzmoney.core.repository.TransactionRepository;
import com.zenzmoney.core.web.dto.AccountResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The user's single account (§1.4, F-1.1). There is no CRUD here on purpose: the
 * account is provisioned once at onboarding and thereafter only read. Everything
 * that writes to the ledger asks this service which account to write to rather
 * than trusting a client-supplied id.
 */
@Service
public class AccountService {

    /** Provisioning only. Reads are already covered by the per-request line MdcContextFilter writes. */
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

    /** The caller's account, provisioning it if onboarding has set a currency but not yet reached it. */
    @Transactional
    public AccountResponse current() {
        return AccountResponse.of(provision(currentUser.requireUser()));
    }

    /**
     * The id every ledger write uses. Resolved from the authenticated user, never
     * from the request body — a client that could name an account could name
     * someone else's.
     */
    @Transactional
    public String requireAccountId(User user) {
        return provision(user).getId();
    }

    /**
     * Get-or-create the user's one account. Idempotent, so it is safe to call from
     * onboarding and from every write path.
     *
     * <p>The unique index on {@code user_id} is the real guard: two concurrent
     * first-writes both miss the {@code findByUserId} and both insert, and the
     * loser gets a constraint violation rather than a second account. Re-reading
     * on that violation turns the race into a no-op instead of a 500.
     */
    @Transactional
    public Account provision(User user) {
        return accountRepository.findByUserId(user.getId())
                .orElseGet(() -> create(user));
    }

    /**
     * Re-denominate the account while the signup currency is still provisional
     * (F-1.27). {@link OnboardingService} is the only caller, and only on the path
     * where the user has not yet confirmed a currency.
     *
     * <p>This exists because the account is provisioned lazily on first use, which
     * can happen <em>before</em> onboarding runs — a user who opens the app, lets it
     * read {@code /account}, and only then picks a currency would otherwise be left
     * with preferences saying one thing and their account saying another.
     *
     * <p>Refuses once anything is recorded. A stored amount is a bare minor-unit
     * integer whose denomination lives only in this column, so flipping it would
     * silently turn 1000 LKR into 1000 USD (§0.3).
     */
    @Transactional
    public void redenominate(User user, String currency) {
        Account account = accountRepository.findByUserId(user.getId()).orElse(null);
        if (account == null || account.getCurrency().equals(currency)) {
            return;
        }
        if (transactionRepository.existsByUserId(user.getId())) {
            throw new BadRequestException("Amounts are already recorded in " + account.getCurrency()
                    + "; changing your currency is not supported yet.");
        }
        String previous = account.getCurrency();
        account.setCurrency(currency);
        accountRepository.save(account);
        log.info("Account re-denominated {} -> {} before onboarding (account {}, user {})",
                previous, currency, account.getId(), user.getId());
    }

    private Account create(User user) {
        String currency = user.getActiveCurrency();
        if (currency == null || currency.isBlank()) {
            // The account is denominated in the user's active currency, so it cannot
            // exist before onboarding picks one (F-1.27).
            throw new BadRequestException(
                    "No active currency set; complete onboarding before recording money.");
        }
        Account account = new Account();
        account.setUserId(user.getId());
        account.setCurrency(currency.toUpperCase());
        try {
            Account saved = accountRepository.saveAndFlush(account);
            log.info("Account provisioned: currency={} (account {}, user {})",
                    saved.getCurrency(), saved.getId(), user.getId());
            return saved;
        } catch (DataIntegrityViolationException e) {
            // Lost the insert race; the winner's row is the account.
            log.debug("Account insert raced for user {}; re-reading the winner", user.getId());
            return accountRepository.findByUserId(user.getId()).orElseThrow(() -> e);
        }
    }
}
