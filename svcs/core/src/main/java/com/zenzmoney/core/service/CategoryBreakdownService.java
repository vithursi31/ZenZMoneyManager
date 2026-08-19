package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.TimeUtils;
import com.zenzmoney.common.domain.TransactionType;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.CategoryBreakdownRow;
import com.zenzmoney.core.repository.TransactionRepository;
import com.zenzmoney.core.web.dto.CategoryBreakdownResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.List;

/**
 * Income and expenses over a period, split by category (F-1.19).
 *
 * <p>Like the monthly position it stores and caches nothing — the buckets are one
 * grouped aggregate over the same rows the transaction list returns, so a report can
 * never drift from the ledger it describes.
 */
@Service
public class CategoryBreakdownService {

    private final TransactionRepository transactionRepository;
    private final AccountService accountService;
    private final CurrentUserService currentUser;

    public CategoryBreakdownService(TransactionRepository transactionRepository,
                                    AccountService accountService,
                                    CurrentUserService currentUser) {
        this.transactionRepository = transactionRepository;
        this.accountService = accountService;
        this.currentUser = currentUser;
    }

    /**
     * @param startDate ISO {@code yyyy-MM-dd}, inclusive, in the caller's timezone
     * @param endDate   ISO {@code yyyy-MM-dd}, inclusive, in the caller's timezone
     * @param accountId optional; omit to span every account the caller holds
     */
    @Transactional(readOnly = true)
    public CategoryBreakdownResponse breakdown(String startDate, String endDate, String accountId) {
        User user = currentUser.requireUser();
        ZoneId zone = TimeUtils.zoneOrUtc(user.getTimezone());
        String account = accountService.requireOwnedFilter(accountId, user.getId());
        DateRange range = DateRange.required(startDate, endDate, zone);

        List<CategoryBreakdownRow> totals = transactionRepository.categoryTotalsInWindow(
                user.getId(), range.from(), range.to(), account);

        return new CategoryBreakdownResponse(
                startDate.trim(), endDate.trim(), zone.getId(), range.from(), range.to(),
                user.getActiveCurrency(), account,
                section(totals, TransactionType.INCOME),
                section(totals, TransactionType.EXPENSE));
    }

    /**
     * One direction's slice. The query already ordered by amount descending, so the
     * filtered list keeps that order — biggest spend first is what a report reads like.
     */
    private static CategoryBreakdownResponse.Section section(List<CategoryBreakdownRow> totals,
                                                             TransactionType type) {
        List<CategoryBreakdownResponse.CategoryAmount> categories = totals.stream()
                .filter(t -> t.getType() == type)
                .map(t -> new CategoryBreakdownResponse.CategoryAmount(
                        t.getCategoryId(), t.getName(), t.getParentId(), t.getColor(), t.getIcon(),
                        t.getAmount(), t.getTransactionCount()))
                .toList();
        long total = categories.stream().mapToLong(CategoryBreakdownResponse.CategoryAmount::getAmount).sum();
        return new CategoryBreakdownResponse.Section(total, categories);
    }
}
