package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.CategoryKind;
import com.zenzmoney.common.domain.CategoryStatus;
import com.zenzmoney.common.domain.PaymentMethod;
import com.zenzmoney.common.domain.TimeUtils;
import com.zenzmoney.common.domain.TransactionStatus;
import com.zenzmoney.common.domain.TransactionType;
import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.common.exception.NotFoundException;
import com.zenzmoney.common.i18n.Msg;
import com.zenzmoney.core.entity.Category;
import com.zenzmoney.core.entity.RecurringTransaction;
import com.zenzmoney.core.entity.Transaction;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.CategoryRepository;
import com.zenzmoney.core.repository.TransactionRepository;
import com.zenzmoney.core.web.dto.CreateTransactionRequest;
import com.zenzmoney.core.web.dto.TransactionResponse;
import com.zenzmoney.core.web.dto.UpdateTransactionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The core ledger service (§1.6). Validates the INCOME/EXPENSE rules, resolves the
 * caller's single account and payee, and persists the row. It writes nothing back
 * to the account: the monthly position is summed from these rows on read (§1.10),
 * which is why an edit here cannot leave a stale figure behind.
 */
@Service
public class TransactionService {

    /**
     * Ledger writes are logged at INFO with the amount in minor units and the currency, because a
     * monthly total that looks wrong is reconstructed from these lines: which rows moved, when, and
     * in which direction. Amounts are the user's own money, not a secret — but note and payee text
     * is free-form user input and is deliberately not logged.
     */
    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final AccountService accountService;
    private final PayeeService payeeService;
    private final CurrentUserService currentUser;

    public TransactionService(TransactionRepository transactionRepository,
                              CategoryRepository categoryRepository,
                              AccountService accountService,
                              PayeeService payeeService,
                              CurrentUserService currentUser) {
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
        this.accountService = accountService;
        this.payeeService = payeeService;
        this.currentUser = currentUser;
    }

    @Transactional
    public TransactionResponse create(CreateTransactionRequest req) {
        User user = currentUser.requireUser();
        Transaction txn = new Transaction();
        apply(txn, user, req.getType(), req.getCategoryId(), req.getAmount(), req.getTxnDate(),
                req.getPayeeName(), req.getNote(), req.getTags(), req.getPaymentMethod());
        Transaction saved = transactionRepository.save(txn);
        log.info("Transaction created: {} {} {} dated {} via {} (txn {}, user {})",
                saved.getType(), saved.getAmount(), saved.getCurrency(),
                saved.getTxnDate(), saved.getPaymentMethod(), saved.getId(), user.getId());
        return TransactionResponse.of(saved);
    }

    /** Full replacement (PUT). */
    @Transactional
    public TransactionResponse update(String id, UpdateTransactionRequest req) {
        User user = currentUser.requireUser();
        Transaction txn = requireOwned(id, user.getId());

        long previousDate = txn.getTxnDate();
        apply(txn, user, req.getType(), req.getCategoryId(), req.getAmount(), req.getTxnDate(),
                req.getPayeeName(), req.getNote(), req.getTags(), req.getPaymentMethod());
        Transaction saved = transactionRepository.save(txn);

        // The date is called out because moving a row across a month boundary is the one edit
        // that changes two months' positions, and it is invisible in the amount alone.
        log.info("Transaction updated: {} {} {} dated {} (was {}) (txn {}, user {})",
                saved.getType(), saved.getAmount(), saved.getCurrency(),
                saved.getTxnDate(), previousDate, saved.getId(), user.getId());
        return TransactionResponse.of(saved);
    }

    /**
     * Removes a transaction from every list and every total.
     *
     * <p><b>Soft (§1.6).</b> The row stays and its status flips to
     * {@link TransactionStatus#DELETED}, because other rows point at it — the chat turn
     * that created it (§3.4) and any goal contribution it funded — and a hard delete
     * would leave those referencing nothing. Every aggregate filters on status, so a
     * deleted row is counted by no total.
     */
    @Transactional
    public void delete(String id) {
        String userId = currentUser.requireUserId();
        Transaction txn = requireOwned(id, userId);
        retire(txn, userId);
    }

    /**
     * Ensures a transaction is not live, whether or not it already was.
     *
     * <p>What undo needs: a row the user had already removed by hand must not make
     * undoing the chat turn that created it fail halfway through — which is exactly what
     * a strict delete did when a recurring turn carried both a template and an occurrence.
     *
     * @return true if this call is what retired it.
     */
    @Transactional
    public boolean deleteIfLive(String id) {
        String userId = currentUser.requireUserId();
        Transaction txn = transactionRepository.findByIdAndUserIdAndStatus(
                id, userId, TransactionStatus.ACTIVE).orElse(null);
        if (txn == null) {
            log.debug("Transaction {} is already gone or not live; nothing to retire (user {})", id, userId);
            return false;
        }
        retire(txn, userId);
        return true;
    }

    /**
     * Puts a deleted transaction back. The payoff of deleting softly (§1.6): an
     * accidental removal — including one chat performed on the user's word — is a status
     * flip away from being undone, not a row that has to be re-entered from memory.
     *
     * @return true if this call is what restored it.
     */
    @Transactional
    public boolean restoreIfDeleted(String id) {
        String userId = currentUser.requireUserId();
        Transaction txn = transactionRepository.findByIdAndUserIdAndStatus(
                id, userId, TransactionStatus.DELETED).orElse(null);
        if (txn == null) {
            log.debug("Transaction {} is not deleted; nothing to restore (user {})", id, userId);
            return false;
        }
        txn.setStatus(TransactionStatus.ACTIVE);
        transactionRepository.save(txn);
        log.info("Transaction restored: {} {} {} dated {} (txn {}, user {})",
                txn.getType(), txn.getAmount(), txn.getCurrency(), txn.getTxnDate(), id, userId);
        return true;
    }

    private void retire(Transaction txn, String userId) {
        txn.setStatus(TransactionStatus.DELETED);
        transactionRepository.save(txn);
        // The row survives, but nothing counts it any more — so this is still the line that
        // explains a month's position changing.
        log.info("Transaction deleted: {} {} {} dated {} (txn {}, user {})",
                txn.getType(), txn.getAmount(), txn.getCurrency(),
                txn.getTxnDate(), txn.getId(), userId);
    }

    @Transactional(readOnly = true)
    public TransactionResponse get(String id) {
        return TransactionResponse.of(requireOwned(id, currentUser.requireUserId()));
    }

    /**
     * Lists the caller's transactions, newest first, optionally narrowed by any
     * combination of account, type, and a date range. A user may hold more than
     * one account (F-1.1); omitting {@code accountId} spans all of them.
     *
     * <p>{@code startDate} and {@code endDate} are ISO {@code yyyy-MM-dd} and inclusive
     * at both ends, resolved in the caller's own timezone — the client sends the dates
     * its picker produced and never computes an instant boundary itself.
     */
    @Transactional(readOnly = true)
    public List<TransactionResponse> list(String accountId, String type, String startDate, String endDate) {
        User user = currentUser.requireUser();
        ZoneId zone = TimeUtils.zoneOrUtc(user.getTimezone());
        TransactionType typeFilter = parseType(type);
        DateRange range = DateRange.of(startDate, endDate, zone);
        Long from = range.from();
        Long to = range.to();

        return transactionRepository.findByUserIdAndStatus(user.getId(), TransactionStatus.ACTIVE).stream()
                .filter(t -> accountId == null || accountId.isBlank() || accountId.equals(t.getAccountId()))
                .filter(t -> typeFilter == null || typeFilter == t.getType())
                .filter(t -> from == null || t.getTxnDate() >= from)
                .filter(t -> to == null || t.getTxnDate() < to)
                .sorted(Comparator.comparingLong(Transaction::getTxnDate).reversed())
                .map(TransactionResponse::of)
                .toList();
    }

    /**
     * Generates one ledger row from a recurring template (§1.8), dated at {@code runDate} —
     * the template was validated when it was created, so its fields are copied rather than
     * re-resolved. {@code recurringId} links the row back to its template. Called by the
     * scheduler within the template's transaction, so the generation and the template's
     * {@code nextRunDate} advance commit atomically.
     *
     * <p>{@code currency} is resolved by the caller from the template's account (§1.4) and
     * passed in: a catch-up run generates many rows for one template, and re-reading the
     * same account per row would be a query per occurrence. The row keeps its own copy
     * because a ledger row records what the money <em>was</em>.
     */
    @Transactional
    public TransactionResponse generateFromRecurring(RecurringTransaction template, long runDate,
                                                     String currency) {
        Transaction txn = new Transaction();
        txn.setUserId(template.getUserId());
        txn.setAccountId(template.getAccountId());
        txn.setType(template.getType());
        txn.setCategoryId(template.getCategoryId());
        txn.setAmount(template.getAmount());
        txn.setCurrency(currency);
        txn.setTxnDate(runDate);
        txn.setNote(template.getNote());
        txn.setTags(new ArrayList<>());
        txn.setPayeeId(template.getPayeeId());     // template payee is already resolved
        txn.setPaymentMethod(template.getPaymentMethod());
        txn.setRecurringId(template.getId());
        Transaction saved = transactionRepository.save(txn);
        // Money moved without a user in the request — the scheduler did it. The template id is what
        // links this back to why, and scheduler.log carries the run that triggered it.
        log.info("Transaction generated from recurring template {}: {} {} {} dated {} (txn {}, user {})",
                template.getId(), saved.getType(), saved.getAmount(), saved.getCurrency(),
                saved.getTxnDate(), saved.getId(), template.getUserId());
        return TransactionResponse.of(saved);
    }

    // --- internals ---

    /** Validates the ledger rules (§1.6) and writes them onto {@code txn}. */
    private void apply(Transaction txn, User user, TransactionType type, String categoryId,
                       long amount, Long txnDate, String payeeName, String note,
                       List<String> tags, PaymentMethod paymentMethod) {
        if (amount <= 0) {
            throw new BadRequestException(Msg.AMOUNT_NOT_POSITIVE);
        }
        String userId = user.getId();
        Category category = categoryRepository.findByIdAndUserIdAndStatus(categoryId, userId, CategoryStatus.ACTIVE)
                .orElseThrow(() -> new NotFoundException(Msg.CATEGORY_NOT_FOUND));
        CategoryKind expected = type == TransactionType.INCOME ? CategoryKind.INCOME : CategoryKind.EXPENSE;
        if (category.getKind() != expected) {
            throw new BadRequestException(Msg.CATEGORY_KIND_MISMATCH);
        }

        txn.setUserId(userId);
        // Server-resolved, never from the request: a client that could name an account could name
        // someone else's. It is also the single seam multi-account (F-F.1) would reopen.
        txn.setAccountId(accountService.requireAccountId(user));
        txn.setType(type);
        txn.setCategoryId(categoryId);
        txn.setAmount(amount);
        txn.setCurrency(user.getActiveCurrency());
        txn.setTxnDate(txnDate != null && txnDate > 0 ? txnDate : TimeUtils.now());
        txn.setNote(note);
        txn.setTags(tags != null ? tags : new ArrayList<>());
        txn.setPayeeId(payeeService.resolveOrCreate(userId, payeeName));
        txn.setPaymentMethod(paymentMethod);
    }

    /** A live row. A deleted one answers 404 exactly as a missing one does. */
    private Transaction requireOwned(String id, String userId) {
        return transactionRepository.findByIdAndUserIdAndStatus(id, userId, TransactionStatus.ACTIVE)
                .orElseThrow(() -> new NotFoundException(Msg.TRANSACTION_NOT_FOUND));
    }

    /** A bad filter value fails at the seam with a clear message, not a silent empty-list result. */
    private static TransactionType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return TransactionType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(Msg.TRANSACTION_UNKNOWN_TYPE, raw);
        }
    }
}
