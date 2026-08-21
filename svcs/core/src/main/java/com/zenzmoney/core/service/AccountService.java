package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.AccountStatus;
import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.common.exception.NotFoundException;
import com.zenzmoney.common.i18n.Msg;
import com.zenzmoney.core.entity.Account;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.AccountRepository;
import com.zenzmoney.core.repository.TransactionRepository;
import com.zenzmoney.core.web.dto.AccountResponse;
import com.zenzmoney.core.web.dto.CreateAccountRequest;
import com.zenzmoney.core.web.dto.UpdateAccountNameRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AccountService {

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
    public AccountResponse current() {
        return AccountResponse.of(provision(currentUser.requireUser()));
    }

    @Transactional
    public List<AccountResponse> listActive() {
        String userId = currentUser.requireUserId();
        return accountRepository.findByUserIdAndStatus(userId, AccountStatus.ACTIVE).stream()
                .map(AccountResponse::of)
                .toList();
    }

    @Transactional
    public AccountResponse findOne(String accountId) {
        String userId = currentUser.requireUserId();
        return AccountResponse.of(accountRepository.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> new NotFoundException(Msg.ACCOUNT_NOT_FOUND)));
    }

    @Transactional
    public AccountResponse create(CreateAccountRequest req) {
        User user = currentUser.requireUser();
        return AccountResponse.of(create(user, normalizeName(req.getName())));
    }

    @Transactional
    public AccountResponse updateName(String accountId, UpdateAccountNameRequest req) {
        String userId = currentUser.requireUserId();
        Account account = accountRepository.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> new NotFoundException(Msg.ACCOUNT_NOT_FOUND));
        if (account.getStatus() == AccountStatus.DELETED) {
            throw new BadRequestException(Msg.ACCOUNT_RENAME_DELETED);
        }
        String name = req.getName().trim();
        account.setName(name);
        accountRepository.save(account);
        log.info("Account renamed: nameLength={} (account {}, user {})", name.length(), accountId, userId);
        return AccountResponse.of(account);
    }

    @Transactional
    public void delete(String accountId) {
        String userId = currentUser.requireUserId();
        Account account = accountRepository.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> new NotFoundException(Msg.ACCOUNT_NOT_FOUND));
        if (account.getStatus() == AccountStatus.DELETED) {
            throw new BadRequestException(Msg.ACCOUNT_ALREADY_DELETED);
        }
        if (account.getStatus() == AccountStatus.ACTIVE
                && accountRepository.countByUserIdAndStatus(userId, AccountStatus.ACTIVE) <= 1) {
            throw new BadRequestException(Msg.ACCOUNT_LAST_ACTIVE);
        }
        account.setStatus(AccountStatus.DELETED);
        accountRepository.save(account);
        log.info("Account deleted (soft): account={}, user={}", accountId, userId);
    }

    @Transactional
    public String requireAccountId(User user) {
        return provision(user).getId();
    }

    /**
     * Validates an optional account filter for a read: null or blank passes through as
     * "every account", anything else must belong to the caller. An unknown id is a 404
     * rather than a silently empty result — a summary of 0.00 is a wrong answer
     * presented as a fact.
     */
    @Transactional(readOnly = true)
    public String requireOwnedFilter(String accountId, String userId) {
        if (accountId == null || accountId.isBlank()) {
            return null;
        }
        String trimmed = accountId.trim();
        accountRepository.findByIdAndUserId(trimmed, userId)
                .orElseThrow(() -> new NotFoundException(Msg.ACCOUNT_NOT_FOUND));
        return trimmed;
    }

    @Transactional
    public Account provision(User user) {
        return accountRepository.findFirstByUserIdAndStatusOrderByCreatedTimeAsc(user.getId(), AccountStatus.ACTIVE)
                .orElseGet(() -> create(user, null));
    }

    @Transactional
    public void redenominate(User user, String currency) {
        Account account = accountRepository
                .findFirstByUserIdAndStatusOrderByCreatedTimeAsc(user.getId(), AccountStatus.ACTIVE)
                .orElse(null);
        if (account == null || account.getCurrency().equals(currency)) {
            return;
        }
        if (transactionRepository.existsByUserId(user.getId())) {
            throw new BadRequestException(Msg.ACCOUNT_CURRENCY_LOCKED, account.getCurrency());
        }
        String previous = account.getCurrency();
        account.setCurrency(currency);
        accountRepository.save(account);
        log.info("Account re-denominated {} -> {} before onboarding (account {}, user {})",
                previous, currency, account.getId(), user.getId());
    }

    private Account create(User user, String name) {
        String currency = user.getActiveCurrency();
        if (currency == null || currency.isBlank()) {
            throw new BadRequestException(Msg.ACCOUNT_NO_CURRENCY);
        }
        Account account = new Account();
        account.setUserId(user.getId());
        account.setCurrency(currency.toUpperCase());
        account.setName(name);
        try {
            Account saved = accountRepository.saveAndFlush(account);
            log.info("Account provisioned: currency={} nameLength={} (account {}, user {})",
                    saved.getCurrency(), name == null ? 0 : name.length(), saved.getId(), user.getId());
            return saved;
        } catch (DataIntegrityViolationException e) {
            log.debug("Account insert raced for user {}; re-reading the winner", user.getId());
            return accountRepository
                    .findFirstByUserIdAndStatusOrderByCreatedTimeAsc(user.getId(), AccountStatus.ACTIVE)
                    .orElseThrow(() -> e);
        }
    }

    private static String normalizeName(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
