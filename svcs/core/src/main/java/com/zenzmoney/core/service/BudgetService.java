package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.AccountStatus;
import com.zenzmoney.common.domain.BudgetStatus;
import com.zenzmoney.common.domain.BudgetPeriod;
import com.zenzmoney.common.domain.CategoryKind;
import com.zenzmoney.common.domain.TimeUtils;
import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.common.exception.NotFoundException;
import com.zenzmoney.core.entity.Account;
import com.zenzmoney.core.entity.Budget;
import com.zenzmoney.core.entity.Category;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.AccountRepository;
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

import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

@Service
public class BudgetService {

    private static final Logger log = LoggerFactory.getLogger(BudgetService.class);

    private final BudgetRepository budgetRepository;
    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final CurrentUserService currentUser;

    public BudgetService(BudgetRepository budgetRepository,
                         CategoryRepository categoryRepository,
                         TransactionRepository transactionRepository,
                         AccountRepository accountRepository,
                         CurrentUserService currentUser) {
        this.budgetRepository = budgetRepository;
        this.categoryRepository = categoryRepository;
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.currentUser = currentUser;
    }

    @Transactional
    public BudgetResponse create(CreateBudgetRequest req) {
        User user = currentUser.requireUser();
        String userId = user.getId();

        Account account = accountRepository.findByIdAndUserId(req.getAccountId(), userId)
                .orElseThrow(() -> new NotFoundException("Account not found"));
        if (account.getStatus() == AccountStatus.DELETED) {
            throw new BadRequestException("Cannot create a budget for a deleted account.");
        }

        String categoryId = normalizeId(req.getCategoryId());
        if (categoryId != null) {
            Category category = categoryRepository.findByIdAndUserId(categoryId, userId)
                    .orElseThrow(() -> new NotFoundException("Category not found"));
            if (category.getKind() != CategoryKind.EXPENSE) {
                throw new BadRequestException("A budget category must be an EXPENSE category.");
            }
        }
        requireNoActiveDuplicate(userId, account.getId(), categoryId, req.getPeriod(), null);

        Budget budget = new Budget();
        budget.setUserId(userId);
        budget.setAccountId(account.getId());
        budget.setCategoryId(categoryId);
        budget.setPeriod(req.getPeriod());
        budget.setAmountLimit(req.getAmountLimit());
        budget.setRollover(req.isRollover());
        budget.setStatus(BudgetStatus.ACTIVE);
        Budget saved = budgetRepository.save(budget);
        log.info("Budget created: limit={} period={} account={} category={} rollover={} (budget {}, user {})",
                saved.getAmountLimit(), saved.getPeriod(), saved.getAccountId(),
                saved.getCategoryId() == null ? "all" : saved.getCategoryId(),
                saved.isRollover(), saved.getId(), userId);
        return toResponse(saved, zoneOf(user));
    }

    @Transactional(readOnly = true)
    public List<BudgetResponse> list(boolean includeArchived) {
        User user = currentUser.requireUser();
        ZoneId zone = zoneOf(user);
        return budgetRepository.findByUserId(user.getId()).stream()
                .filter(b -> includeArchived || b.getStatus() != BudgetStatus.ARCHIVED)
                .map(b -> toResponse(b, zone))
                .toList();
    }

    @Transactional(readOnly = true)
    public BudgetResponse get(String id) {
        User user = currentUser.requireUser();
        return toResponse(requireOwned(id, user.getId()), zoneOf(user));
    }

    @Transactional
    public BudgetResponse update(String id, UpdateBudgetRequest req) {
        User user = currentUser.requireUser();
        Budget budget = requireOwned(id, user.getId());
        if (req.getAmountLimit() != null) {
            if (req.getAmountLimit() <= 0) {
                throw new BadRequestException("Amount limit must be positive.");
            }
            budget.setAmountLimit(req.getAmountLimit());
        }
        if (req.getRollover() != null) {
            budget.setRollover(req.getRollover());
        }
        log.info("Budget updated: limit={} (budget {}, user {})", budget.getAmountLimit(), id, budget.getUserId());
        return toResponse(budgetRepository.save(budget), zoneOf(user));
    }

    @Transactional
    public BudgetResponse archive(String id) {
        User user = currentUser.requireUser();
        Budget budget = requireOwned(id, user.getId());
        budget.setStatus(BudgetStatus.ARCHIVED);
        log.info("Budget archived: (budget {}, user {})", id, budget.getUserId());
        return toResponse(budgetRepository.save(budget), zoneOf(user));
    }

    /** Budgets are pure planning rows — nothing references them, so this is a hard delete. */
    @Transactional
    public void delete(String id) {
        Budget budget = requireOwned(id, currentUser.requireUserId());
        budgetRepository.delete(budget);
        log.info("Budget deleted: limit={} period={} account={} (budget {}, user {})",
                budget.getAmountLimit(), budget.getPeriod(), budget.getAccountId(), id, budget.getUserId());
    }

    // --- internals ---

    private Budget requireOwned(String id, String userId) {
        return budgetRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new NotFoundException("Budget not found"));
    }

    private static ZoneId zoneOf(User user) {
        return TimeUtils.zoneOrUtc(user.getTimezone());
    }

    /**
     * Enforces "at most one active budget per (accountId, categoryId, period)" (§1.7).
     * Null category (overall budget) is compared with {@link Objects#equals}, since a
     * derived query with a null parameter would generate {@code = null} (never true)
     * rather than {@code IS NULL}. {@code excludeId} skips the row being updated.
     */
    private void requireNoActiveDuplicate(String userId, String accountId, String categoryId,
                                          BudgetPeriod period, String excludeId) {
        boolean clash = budgetRepository.findByUserId(userId).stream()
                .filter(b -> b.getStatus() == BudgetStatus.ACTIVE)
                .filter(b -> !b.getId().equals(excludeId))
                .filter(b -> b.getAccountId().equals(accountId))
                .filter(b -> b.getPeriod() == period)
                .anyMatch(b -> Objects.equals(b.getCategoryId(), categoryId));
        if (clash) {
            throw new BadRequestException(
                    "An active budget already exists for this account, category and period.");
        }
    }

    private BudgetResponse toResponse(Budget b, ZoneId zone) {
        long[] window = currentWindow(b.getPeriod(), zone, TimeUtils.now());
        long spent = b.getCategoryId() != null
                ? transactionRepository.sumExpenseByCategoryInWindow(
                        b.getUserId(), b.getCategoryId(), window[0], window[1])
                : transactionRepository.sumExpenseInWindow(b.getUserId(), window[0], window[1]);
        String currency = accountRepository.findById(b.getAccountId()).orElseThrow().getCurrency();
        return BudgetResponse.of(b, currency, spent, window[0], window[1]);
    }

    /** The calendar period ({@code period}, in {@code zone}) containing {@code now}. */
    static long[] currentWindow(BudgetPeriod period, ZoneId zone, long now) {
        return switch (period) {
            case MONTHLY -> {
                YearMonth ym = TimeUtils.monthOf(now, zone);
                yield new long[]{TimeUtils.startOfMonth(ym, zone), TimeUtils.startOfMonth(ym.plusMonths(1), zone)};
            }
            case YEARLY -> {
                Year y = TimeUtils.yearOf(now, zone);
                yield new long[]{TimeUtils.startOfYear(y, zone), TimeUtils.startOfYear(y.plusYears(1), zone)};
            }
        };
    }

    private static String normalizeId(String id) {
        return (id == null || id.isBlank()) ? null : id;
    }
}
