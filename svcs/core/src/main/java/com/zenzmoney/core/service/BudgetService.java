package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.BudgetStatus;
import com.zenzmoney.common.domain.BudgetPeriod;
import com.zenzmoney.common.domain.CategoryKind;
import com.zenzmoney.common.domain.TimeUtils;
import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.common.exception.NotFoundException;
import com.zenzmoney.core.entity.Budget;
import com.zenzmoney.core.entity.Category;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.BudgetRepository;
import com.zenzmoney.core.repository.CategoryRepository;
import com.zenzmoney.core.repository.TransactionRepository;
import com.zenzmoney.core.web.dto.BudgetResponse;
import com.zenzmoney.core.web.dto.CreateBudgetRequest;
import com.zenzmoney.core.web.dto.UpdateBudgetRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;

/**
 * CRUD for {@link Budget}, scoped to the authenticated user (§1.7). A budget stores
 * only the cap; "spent" is derived from EXPENSE transactions in the current period
 * window and never persisted, so it can never drift from the ledger. Period windows
 * are anchored at {@code startDate} and advance by the {@link BudgetPeriod} (UTC).
 */
@Service
public class BudgetService {

    /** Mutations only. Reads are already covered by the per-request line MdcContextFilter writes. */
    private static final Logger log = LoggerFactory.getLogger(BudgetService.class);

    private final BudgetRepository budgetRepository;
    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;
    private final CurrentUserService currentUser;

    public BudgetService(BudgetRepository budgetRepository,
                         CategoryRepository categoryRepository,
                         TransactionRepository transactionRepository,
                         CurrentUserService currentUser) {
        this.budgetRepository = budgetRepository;
        this.categoryRepository = categoryRepository;
        this.transactionRepository = transactionRepository;
        this.currentUser = currentUser;
    }

    @Transactional
    public BudgetResponse create(CreateBudgetRequest req) {
        User user = currentUser.requireUser();
        String userId = user.getId();

        String categoryId = normalizeId(req.getCategoryId());
        if (categoryId != null) {
            Category category = categoryRepository.findByIdAndUserId(categoryId, userId)
                    .orElseThrow(() -> new NotFoundException("Category not found"));
            if (category.getKind() != CategoryKind.EXPENSE) {
                throw new BadRequestException("A budget category must be an EXPENSE category.");
            }
        }
        requireNoActiveDuplicate(userId, categoryId, req.getPeriod(), null);

        Budget budget = new Budget();
        budget.setUserId(userId);
        budget.setCategoryId(categoryId);
        budget.setPeriod(req.getPeriod());
        budget.setAmountLimit(req.getAmountLimit());
        budget.setCurrency(resolveCurrency(user, req.getCurrency()));
        budget.setStartDate(req.getStartDate() != null && req.getStartDate() > 0
                ? req.getStartDate() : TimeUtils.now());
        budget.setRollover(req.isRollover());
        budget.setStatus(BudgetStatus.ACTIVE);
        Budget saved = budgetRepository.save(budget);
        log.info("Budget created: limit={} {} period={} category={} rollover={} (budget {}, user {})",
                saved.getAmountLimit(), saved.getCurrency(), saved.getPeriod(),
                saved.getCategoryId() == null ? "all" : saved.getCategoryId(),
                saved.isRollover(), saved.getId(), userId);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<BudgetResponse> list(boolean includeArchived) {
        String userId = currentUser.requireUserId();
        return budgetRepository.findByUserId(userId).stream()
                .filter(b -> includeArchived || b.getStatus() != BudgetStatus.ARCHIVED)
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public BudgetResponse get(String id) {
        return toResponse(requireOwned(id, currentUser.requireUserId()));
    }

    @Transactional
    public BudgetResponse update(String id, UpdateBudgetRequest req) {
        Budget budget = requireOwned(id, currentUser.requireUserId());
        if (req.getAmountLimit() != null) {
            if (req.getAmountLimit() <= 0) {
                throw new BadRequestException("Amount limit must be positive.");
            }
            budget.setAmountLimit(req.getAmountLimit());
        }
        if (req.getStartDate() != null && req.getStartDate() > 0) {
            budget.setStartDate(req.getStartDate());
        }
        if (req.getRollover() != null) {
            budget.setRollover(req.getRollover());
        }
        log.info("Budget updated: limit={} {} (budget {}, user {})",
                budget.getAmountLimit(), budget.getCurrency(), id, budget.getUserId());
        return toResponse(budgetRepository.save(budget));
    }

    @Transactional
    public BudgetResponse archive(String id) {
        Budget budget = requireOwned(id, currentUser.requireUserId());
        budget.setStatus(BudgetStatus.ARCHIVED);
        log.info("Budget archived: (budget {}, user {})", id, budget.getUserId());
        return toResponse(budgetRepository.save(budget));
    }

    /** Budgets are pure planning rows — nothing references them, so this is a hard delete. */
    @Transactional
    public void delete(String id) {
        Budget budget = requireOwned(id, currentUser.requireUserId());
        budgetRepository.delete(budget);
        // Hard delete — this line is the only surviving record of the budget.
        log.info("Budget deleted: limit={} {} period={} (budget {}, user {})",
                budget.getAmountLimit(), budget.getCurrency(), budget.getPeriod(),
                id, budget.getUserId());
    }

    // --- internals ---

    private Budget requireOwned(String id, String userId) {
        return budgetRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new NotFoundException("Budget not found"));
    }

    /**
     * Enforces "at most one active budget per (categoryId, period)" (§1.7). Null
     * category (overall budget) is compared with {@link Objects#equals}, since a
     * derived query with a null parameter would generate {@code = null} (never true)
     * rather than {@code IS NULL}. {@code excludeId} skips the row being updated.
     */
    private void requireNoActiveDuplicate(String userId, String categoryId,
                                          BudgetPeriod period, String excludeId) {
        boolean clash = budgetRepository.findByUserId(userId).stream()
                .filter(b -> b.getStatus() == BudgetStatus.ACTIVE)
                .filter(b -> !b.getId().equals(excludeId))
                .filter(b -> b.getPeriod() == period)
                .anyMatch(b -> Objects.equals(b.getCategoryId(), categoryId));
        if (clash) {
            throw new BadRequestException(
                    "An active budget already exists for this category and period.");
        }
    }

    private BudgetResponse toResponse(Budget b) {
        long[] window = currentWindow(b.getStartDate(), b.getPeriod(), TimeUtils.now());
        long spent = b.getCategoryId() != null
                ? transactionRepository.sumExpenseByCategoryInWindow(
                        b.getUserId(), b.getCategoryId(), window[0], window[1])
                : transactionRepository.sumExpenseInWindow(b.getUserId(), window[0], window[1]);
        return BudgetResponse.of(b, spent, window[0], window[1]);
    }

    /**
     * The period window {@code [start, end)} containing {@code now}, anchored at
     * {@code startMillis} and advancing by {@code period}. When {@code now} precedes
     * the anchor, the first window is returned. UTC boundaries (multi-currency and
     * per-user timezones are additive later).
     */
    static long[] currentWindow(long startMillis, BudgetPeriod period, long now) {
        ZonedDateTime windowStart = Instant.ofEpochMilli(startMillis).atZone(ZoneOffset.UTC);
        ZonedDateTime windowEnd = advance(windowStart, period);
        while (now >= windowEnd.toInstant().toEpochMilli()) {
            windowStart = windowEnd;
            windowEnd = advance(windowStart, period);
        }
        return new long[]{windowStart.toInstant().toEpochMilli(), windowEnd.toInstant().toEpochMilli()};
    }

    private static ZonedDateTime advance(ZonedDateTime from, BudgetPeriod period) {
        return switch (period) {
            case WEEKLY -> from.plusWeeks(1);
            case MONTHLY -> from.plusMonths(1);
            case YEARLY -> from.plusYears(1);
        };
    }

    private static String normalizeId(String id) {
        return (id == null || id.isBlank()) ? null : id;
    }

    /**
     * MVP single-currency rule (§0.3): use the user's active currency; only when it
     * is not yet set may the request seed one (ISO-4217).
     */
    private String resolveCurrency(User user, String requested) {
        String active = user.getActiveCurrency();
        if (active != null && !active.isBlank()) {
            return active.toUpperCase();
        }
        if (requested != null && !requested.isBlank()) {
            return requested.toUpperCase();
        }
        throw new BadRequestException("No active currency set; provide a currency for the budget.");
    }
}
