package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.AccountStatus;
import com.zenzmoney.common.domain.CategoryKind;
import com.zenzmoney.common.domain.TimeUtils;
import com.zenzmoney.common.domain.TransactionType;
import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.common.exception.NotFoundException;
import com.zenzmoney.core.entity.Account;
import com.zenzmoney.core.entity.Category;
import com.zenzmoney.core.entity.RecurringTransaction;
import com.zenzmoney.core.entity.Transaction;
import com.zenzmoney.core.repository.AccountRepository;
import com.zenzmoney.core.repository.CategoryRepository;
import com.zenzmoney.core.repository.TransactionRepository;
import com.zenzmoney.core.web.dto.CreateTransactionRequest;
import com.zenzmoney.core.web.dto.TransactionResponse;
import com.zenzmoney.core.web.dto.UpdateTransactionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The core ledger service (§1.6). Validates the INCOME/EXPENSE/TRANSFER rules,
 * persists the transaction, and re-derives affected account balances from the
 * ledger (§1.10) inside the same DB transaction. Payee names resolve to
 * {@link com.zenzmoney.core.entity.Payee} rows via {@link PayeeService}.
 */
@Service
public class TransactionService {

    /**
     * Ledger writes are logged at INFO with the amount in minor units and the currency, because a
     * balance that looks wrong is reconstructed from this: the transaction that moved it, and the
     * derivation that followed. Amounts are the user's own money, not a secret — but note and payee
     * text is free-form user input and is deliberately not logged.
     */
    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final CategoryRepository categoryRepository;
    private final PayeeService payeeService;
    private final CurrentUserService currentUser;

    public TransactionService(TransactionRepository transactionRepository,
                              AccountRepository accountRepository,
                              CategoryRepository categoryRepository,
                              PayeeService payeeService,
                              CurrentUserService currentUser) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.categoryRepository = categoryRepository;
        this.payeeService = payeeService;
        this.currentUser = currentUser;
    }

    @Transactional
    public TransactionResponse create(CreateTransactionRequest req) {
        String userId = currentUser.requireUserId();
        Transaction txn = new Transaction();
        apply(txn, userId, req.getType(), req.getAccountId(), req.getCategoryId(), req.getAmount(),
                req.getTransferAccountId(), req.getTxnDate(), req.getPayeeName(), req.getNote(), req.getTags());
        Transaction saved = transactionRepository.save(txn);
        recomputeAffected(saved);
        log.info("Transaction created: {} {} {} on account {} (txn {}, user {})",
                saved.getType(), saved.getAmount(), saved.getCurrency(),
                saved.getAccountId(), saved.getId(), userId);
        return TransactionResponse.of(saved);
    }

    /** Full replacement (PUT). Re-derives both the previously- and newly-affected accounts. */
    @Transactional
    public TransactionResponse update(String id, UpdateTransactionRequest req) {
        String userId = currentUser.requireUserId();
        Transaction txn = requireOwned(id, userId);

        Set<String> affected = affectedAccounts(txn);   // before the edit
        apply(txn, userId, req.getType(), req.getAccountId(), req.getCategoryId(), req.getAmount(),
                req.getTransferAccountId(), req.getTxnDate(), req.getPayeeName(), req.getNote(), req.getTags());
        Transaction saved = transactionRepository.save(txn);

        affected.addAll(affectedAccounts(saved));        // plus the accounts it now touches
        affected.forEach(this::recompute);
        log.info("Transaction updated: {} {} {} on account {} (txn {}, user {}, rederived {} account(s))",
                saved.getType(), saved.getAmount(), saved.getCurrency(),
                saved.getAccountId(), saved.getId(), userId, affected.size());
        return TransactionResponse.of(saved);
    }

    @Transactional
    public void delete(String id) {
        String userId = currentUser.requireUserId();
        Transaction txn = requireOwned(id, userId);
        Set<String> affected = affectedAccounts(txn);
        transactionRepository.delete(txn);
        affected.forEach(this::recompute);
        // A hard delete — the row is gone, so this line is the only remaining record that it existed.
        log.info("Transaction deleted: {} {} {} on account {} (txn {}, user {})",
                txn.getType(), txn.getAmount(), txn.getCurrency(),
                txn.getAccountId(), id, userId);
    }

    @Transactional(readOnly = true)
    public TransactionResponse get(String id) {
        return TransactionResponse.of(requireOwned(id, currentUser.requireUserId()));
    }

    /** Lists the caller's transactions, optionally by account and/or date range, newest first. */
    @Transactional(readOnly = true)
    public List<TransactionResponse> list(String accountId, Long from, Long to) {
        String userId = currentUser.requireUserId();
        List<Transaction> base = (accountId != null && !accountId.isBlank())
                ? transactionRepository.findByUserIdAndAccountId(userId, accountId)
                : transactionRepository.findByUserId(userId);
        return base.stream()
                .filter(t -> from == null || t.getTxnDate() >= from)
                .filter(t -> to == null || t.getTxnDate() <= to)
                .sorted(Comparator.comparingLong(Transaction::getTxnDate).reversed())
                .map(TransactionResponse::of)
                .toList();
    }

    /**
     * Generates one ledger row from a recurring template (§1.8), dated at {@code runDate},
     * and re-derives affected balances — the same validation and balance rules as a normal
     * transaction. The template's already-resolved {@code payeeId} is copied directly, and
     * {@code recurringId} links the row back to its template. Called by the scheduler within
     * the template's transaction, so the generation and the template's {@code nextRunDate}
     * advance commit atomically.
     */
    @Transactional
    public TransactionResponse generateFromRecurring(RecurringTransaction template, long runDate) {
        Transaction txn = new Transaction();
        apply(txn, template.getUserId(), template.getType(), template.getAccountId(),
                template.getCategoryId(), template.getAmount(), template.getTransferAccountId(),
                runDate, null, template.getNote(), null);
        txn.setPayeeId(template.getPayeeId());     // template payee is already resolved
        txn.setRecurringId(template.getId());
        Transaction saved = transactionRepository.save(txn);
        recomputeAffected(saved);
        // Money moved without a user in the request — the scheduler did it. The template id is what
        // links this back to why, and scheduler.log carries the run that triggered it.
        log.info("Transaction generated from recurring template {}: {} {} {} on account {} (txn {}, user {})",
                template.getId(), saved.getType(), saved.getAmount(), saved.getCurrency(),
                saved.getAccountId(), saved.getId(), template.getUserId());
        return TransactionResponse.of(saved);
    }

    // --- internals ---

    /** Validates the type-specific rules (§1.6) and writes them onto {@code txn}. */
    private void apply(Transaction txn, String userId, TransactionType type, String accountId,
                       String categoryId, long amount, String transferAccountId, Long txnDate,
                       String payeeName, String note, List<String> tags) {
        if (amount <= 0) {
            throw new BadRequestException("Amount must be positive.");
        }
        Account source = requireActiveAccount(accountId, userId);

        txn.setUserId(userId);
        txn.setAccountId(accountId);
        txn.setType(type);
        txn.setAmount(amount);
        txn.setCurrency(source.getCurrency());
        txn.setTxnDate(txnDate != null && txnDate > 0 ? txnDate : TimeUtils.now());
        txn.setNote(note);
        txn.setTags(tags != null ? tags : new ArrayList<>());
        txn.setPayeeId(payeeService.resolveOrCreate(userId, payeeName));

        if (type == TransactionType.TRANSFER) {
            if (isBlank(transferAccountId)) {
                throw new BadRequestException("A transfer requires a destination account.");
            }
            if (transferAccountId.equals(accountId)) {
                throw new BadRequestException("A transfer's destination must differ from its source.");
            }
            if (!isBlank(categoryId)) {
                throw new BadRequestException("A transfer must not have a category.");
            }
            Account dest = requireActiveAccount(transferAccountId, userId);
            if (!source.getCurrency().equals(dest.getCurrency())) {
                throw new BadRequestException("Transfer accounts must share the same currency.");
            }
            txn.setTransferAccountId(transferAccountId);
            txn.setCategoryId(null);
        } else {   // INCOME or EXPENSE
            if (!isBlank(transferAccountId)) {
                throw new BadRequestException("Only a transfer may set a destination account.");
            }
            if (isBlank(categoryId)) {
                throw new BadRequestException("Income and expense require a category.");
            }
            Category category = categoryRepository.findByIdAndUserId(categoryId, userId)
                    .orElseThrow(() -> new NotFoundException("Category not found"));
            CategoryKind expected = type == TransactionType.INCOME ? CategoryKind.INCOME : CategoryKind.EXPENSE;
            if (category.getKind() != expected) {
                throw new BadRequestException("Category kind must match the transaction type.");
            }
            txn.setCategoryId(categoryId);
            txn.setTransferAccountId(null);
        }
    }

    /** Re-derives {@code currentBalance} for an account from its ledger (§1.10). */
    private void recompute(String accountId) {
        Account acc = accountRepository.findById(accountId).orElse(null);
        if (acc == null) {
            return;
        }
        long income = transactionRepository.sumAmountByAccountIdAndType(accountId, TransactionType.INCOME);
        long expense = transactionRepository.sumAmountByAccountIdAndType(accountId, TransactionType.EXPENSE);
        long transferOut = transactionRepository.sumAmountByAccountIdAndType(accountId, TransactionType.TRANSFER);
        long transferIn = transactionRepository.sumTransferInByAccountId(accountId);
        long derived = acc.getOpeningBalance() + income - expense - transferOut + transferIn;
        // DEBUG: the inputs to the derivation, so a wrong cached balance can be checked against the
        // ledger sums that produced it rather than re-deriving by hand (§1.10).
        log.debug("Balance re-derived for account {}: opening={} + in={} - out={} - xferOut={} + xferIn={} = {} (was {})",
                accountId, acc.getOpeningBalance(), income, expense, transferOut, transferIn,
                derived, acc.getCurrentBalance());
        acc.setCurrentBalance(derived);
        accountRepository.save(acc);
    }

    private void recomputeAffected(Transaction txn) {
        affectedAccounts(txn).forEach(this::recompute);
    }

    private Set<String> affectedAccounts(Transaction txn) {
        Set<String> ids = new LinkedHashSet<>();
        ids.add(txn.getAccountId());
        if (txn.getTransferAccountId() != null) {
            ids.add(txn.getTransferAccountId());
        }
        return ids;
    }

    private Account requireActiveAccount(String id, String userId) {
        Account a = accountRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new NotFoundException("Account not found"));
        if (a.getStatus() == AccountStatus.DELETED) {
            throw new NotFoundException("Account not found");
        }
        if (a.getStatus() == AccountStatus.ARCHIVED) {
            throw new BadRequestException("Account is archived and cannot take transactions.");
        }
        return a;
    }

    private Transaction requireOwned(String id, String userId) {
        return transactionRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new NotFoundException("Transaction not found"));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
