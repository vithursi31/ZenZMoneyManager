package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.AccountStatus;
import com.zenzmoney.common.domain.CategoryKind;
import com.zenzmoney.common.domain.TimeUtils;
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
import com.zenzmoney.core.web.dto.UpdateTransactionRequest;
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
        return TransactionResponse.of(saved);
    }

    @Transactional
    public void delete(String id) {
        String userId = currentUser.requireUserId();
        Transaction txn = requireOwned(id, userId);
        Set<String> affected = affectedAccounts(txn);
        transactionRepository.delete(txn);
        affected.forEach(this::recompute);
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
        acc.setCurrentBalance(acc.getOpeningBalance() + income - expense - transferOut + transferIn);
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
