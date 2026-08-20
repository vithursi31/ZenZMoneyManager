package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.AccountStatus;
import com.zenzmoney.common.domain.BudgetStatus;
import com.zenzmoney.common.domain.BudgetPeriod;
import com.zenzmoney.common.domain.CategoryKind;
import com.zenzmoney.common.domain.CategoryStatus;
import com.zenzmoney.common.domain.TimeUtils;
import com.zenzmoney.common.domain.TransactionType;
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
import com.zenzmoney.core.web.dto.BudgetSummaryResponse;
import com.zenzmoney.core.web.dto.CreateBudgetRequest;
import com.zenzmoney.core.web.dto.UpdateBudgetRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

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
            Category category = categoryRepository.findByIdAndUserIdAndStatus(categoryId, userId, CategoryStatus.ACTIVE)
                    .orElseThrow(() -> new NotFoundException("Category not found"));
            if (category.getKind() != CategoryKind.EXPENSE) {
                throw new BadRequestException("A budget category must be an EXPENSE category.");
            }
        }
        String periodKey = normalizePeriodKey(req.getPeriod(), req.getPeriodKey());
        requireNoActiveDuplicate(userId, account.getId(), categoryId, req.getPeriod(), periodKey);

        Budget budget = new Budget();
        budget.setUserId(userId);
        budget.setAccountId(account.getId());
        budget.setCategoryId(categoryId);
        budget.setPeriod(req.getPeriod());
        budget.setPeriodKey(periodKey);
        budget.setAmountLimit(req.getAmountLimit());
        budget.setRollover(req.isRollover());
        budget.setStatus(BudgetStatus.ACTIVE);
        Budget saved = budgetRepository.save(budget);
        log.info("Budget created: limit={} period={} periodKey={} account={} category={} rollover={} (budget {}, user {})",
                saved.getAmountLimit(), saved.getPeriod(), saved.getPeriodKey(), saved.getAccountId(),
                saved.getCategoryId() == null ? "all" : saved.getCategoryId(),
                saved.isRollover(), saved.getId(), userId);
        return toResponse(saved, zoneOf(user), account.getCurrency());
    }

    @Transactional(readOnly = true)
    public List<BudgetResponse> list(boolean includeArchived) {
        User user = currentUser.requireUser();
        ZoneId zone = zoneOf(user);
        Map<String, String> currencies = currencyByAccount(user.getId());
        return budgetRepository.findByUserId(user.getId()).stream()
                .filter(b -> b.getStatus() != BudgetStatus.DELETED)
                .filter(b -> includeArchived || b.getStatus() != BudgetStatus.ARCHIVED)
                .map(b -> toResponse(b, zone, currencyOf(currencies, b, user)))
                .toList();
    }

    /**
     * The month's plan against its outcome: every active budget the caller set for
     * {@code month} (ISO {@code yyyy-MM}, defaulting to their current month), the
     * caps they add up to, and what has been spent against them so far.
     *
     * <p>Only MONTHLY budgets are included. A yearly cap belongs to a different
     * window, and folding a 1,200,000 annual limit into a month's total would
     * misstate both.
     */
    @Transactional(readOnly = true)
    public BudgetSummaryResponse summary(String month) {
        User user = currentUser.requireUser();
        ZoneId zone = zoneOf(user);
        YearMonth target = parseMonth(month, zone);

        long from = TimeUtils.startOfMonth(target, zone);
        long to = TimeUtils.startOfMonth(target.plusMonths(1), zone);

        Map<String, String> currencies = currencyByAccount(user.getId());
        List<BudgetResponse> budgets = budgetRepository
                .findByUserIdAndPeriodAndPeriodKeyAndStatus(
                        user.getId(), BudgetPeriod.MONTHLY, target.toString(), BudgetStatus.ACTIVE)
                .stream()
                .map(b -> toResponse(b, zone, currencyOf(currencies, b, user)))
                .toList();

        long totalLimit = sumOfCategoryBudgets(budgets, BudgetResponse::getAmountLimit);
        long totalSpent = sumOfCategoryBudgets(budgets, BudgetResponse::getSpent);
        long monthExpenses = transactionRepository.sumAmountByTypeInWindow(
                user.getId(), TransactionType.EXPENSE, from, to, null);

        return new BudgetSummaryResponse(target.toString(), zone.getId(), from, to,
                user.getActiveCurrency(), totalLimit, totalSpent, totalLimit - totalSpent,
                monthExpenses, budgets);
    }

    @Transactional(readOnly = true)
    public BudgetResponse get(String id) {
        User user = currentUser.requireUser();
        return toResponse(requireOwned(id, user.getId()), zoneOf(user));
    }

    @Transactional
    public BudgetResponse update(String id, UpdateBudgetRequest req) {
        User user = currentUser.requireUser();
        Budget budget = requireLive(id, user.getId());
        if (req.getAmountLimit() != null) {
            if (req.getAmountLimit() <= 0) {
                throw new BadRequestException("Amount limit must be positive.");
            }
            budget.setAmountLimit(req.getAmountLimit());
        }
        if (req.getRollover() != null) {
            budget.setRollover(req.getRollover());
        }
        log.info("Budget updated: limit={} periodKey={} (budget {}, user {})",
                budget.getAmountLimit(), budget.getPeriodKey(), id, budget.getUserId());
        return toResponse(budgetRepository.save(budget), zoneOf(user));
    }

    @Transactional
    public BudgetResponse archive(String id) {
        User user = currentUser.requireUser();
        Budget budget = requireLive(id, user.getId());
        budget.setStatus(BudgetStatus.ARCHIVED);
        log.info("Budget archived: (budget {}, user {})", id, budget.getUserId());
        return toResponse(budgetRepository.save(budget), zoneOf(user));
    }

    /**
     * Soft delete: the row stays, its status changes. Nothing references a budget, so
     * removing it would be safe — it is kept anyway because "what did I plan last
     * March" is a question the user can still ask, and because the slot it occupied is
     * freed regardless (the duplicate check and {@code uq_budget_active_slot} both
     * look at ACTIVE rows only).
     */
    @Transactional
    public void delete(String id) {
        String userId = currentUser.requireUserId();
        Budget budget = requireOwned(id, userId);
        if (budget.getStatus() == BudgetStatus.DELETED) {
            throw new BadRequestException("Budget already deleted.");
        }
        budget.setStatus(BudgetStatus.DELETED);
        budgetRepository.save(budget);
        log.info("Budget deleted (soft): limit={} period={} periodKey={} account={} (budget {}, user {})",
                budget.getAmountLimit(), budget.getPeriod(), budget.getPeriodKey(),
                budget.getAccountId(), id, userId);
    }

    // --- internals ---

    private Budget requireOwned(String id, String userId) {
        return budgetRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new NotFoundException("Budget not found"));
    }

    /** Owned and still editable — a deleted budget is readable history, not a live plan. */
    private Budget requireLive(String id, String userId) {
        Budget budget = requireOwned(id, userId);
        if (budget.getStatus() == BudgetStatus.DELETED) {
            throw new BadRequestException("Budget is deleted.");
        }
        return budget;
    }

    private static ZoneId zoneOf(User user) {
        return TimeUtils.zoneOrUtc(user.getTimezone());
    }

    /**
     * Enforces "at most one active budget per (accountId, categoryId, period, periodKey)"
     * (§1.7). Null category (overall budget) is compared with {@link Objects#equals},
     * since a derived query with a null parameter would generate {@code = null} (never
     * true) rather than {@code IS NULL}. The DB backs this with
     * {@code uq_budget_active_slot} for the concurrent case.
     */
    private void requireNoActiveDuplicate(String userId, String accountId, String categoryId,
                                          BudgetPeriod period, String periodKey) {
        boolean clash = budgetRepository
                .findByUserIdAndAccountIdAndPeriodAndPeriodKeyAndStatus(
                        userId, accountId, period, periodKey, BudgetStatus.ACTIVE)
                .stream()
                .anyMatch(b -> Objects.equals(b.getCategoryId(), categoryId));
        if (clash) {
            throw new BadRequestException(
                    "An active budget already exists for this account, category and period.");
        }
    }

    private BudgetResponse toResponse(Budget b, ZoneId zone) {
        String currency = accountRepository.findByIdAndUserId(b.getAccountId(), b.getUserId())
                .map(Account::getCurrency)
                .orElseThrow(() -> new NotFoundException("Account not found"));
        return toResponse(b, zone, currency);
    }

    private BudgetResponse toResponse(Budget b, ZoneId zone, String currency) {
        long[] window = windowFor(b.getPeriod(), b.getPeriodKey(), zone);
        long spent = b.getCategoryId() != null
                ? transactionRepository.sumExpenseByCategoryInWindow(
                        b.getUserId(), b.getCategoryId(), window[0], window[1], b.getAccountId())
                : transactionRepository.sumExpenseInWindow(
                        b.getUserId(), window[0], window[1], b.getAccountId());
        return BudgetResponse.of(b, currency, spent, window[0], window[1]);
    }

    /** One lookup for a whole listing, rather than an account read per budget. */
    private Map<String, String> currencyByAccount(String userId) {
        return accountRepository.findByUserId(userId).stream()
                .collect(Collectors.toMap(Account::getId, Account::getCurrency, (a, b) -> a));
    }

    private static String currencyOf(Map<String, String> currencies, Budget b, User user) {
        return currencies.getOrDefault(b.getAccountId(), user.getActiveCurrency());
    }

    private static long sumOfCategoryBudgets(List<BudgetResponse> budgets,
                                             Function<BudgetResponse, Long> field) {
        return budgets.stream()
                .filter(b -> b.getCategoryId() != null)
                .mapToLong(field::apply)
                .sum();
    }

    /**
     * The window a budget covers — the calendar month or year its {@code periodKey}
     * names, resolved in the owner's zone. Unlike the old model this never depends on
     * "now": a budget set for 2026-07 reports July whether it is read in July or in
     * December, and a July limit never applies to January.
     */
    static long[] windowFor(BudgetPeriod period, String periodKey, ZoneId zone) {
        String key = normalizePeriodKey(period, periodKey);
        return switch (period) {
            case MONTHLY -> {
                YearMonth ym = YearMonth.parse(key);
                yield new long[]{TimeUtils.startOfMonth(ym, zone), TimeUtils.startOfMonth(ym.plusMonths(1), zone)};
            }
            case YEARLY -> {
                Year y = Year.parse(key);
                yield new long[]{TimeUtils.startOfYear(y, zone), TimeUtils.startOfYear(y.plusYears(1), zone)};
            }
        };
    }

    /** {@code yyyy-MM} for MONTHLY, {@code yyyy} for YEARLY — the format the period type implies. */
    private static String normalizePeriodKey(BudgetPeriod period, String periodKey) {
        String key = periodKey == null ? "" : periodKey.trim();
        try {
            return switch (period) {
                case MONTHLY -> YearMonth.parse(key).toString();
                case YEARLY -> Year.parse(key).toString();
            };
        } catch (DateTimeParseException e) {
            throw new BadRequestException(period == BudgetPeriod.MONTHLY
                    ? "periodKey must be yyyy-MM for a MONTHLY budget, e.g. 2026-08."
                    : "periodKey must be yyyy for a YEARLY budget, e.g. 2026.");
        }
    }

    /**
     * "Now" is resolved in the user's own zone, so someone in {@code Asia/Colombo}
     * asking on the 1st gets their August, not the server's July.
     */
    private static YearMonth parseMonth(String month, ZoneId zone) {
        if (month == null || month.isBlank()) {
            return TimeUtils.monthOf(TimeUtils.now(), zone);
        }
        try {
            return YearMonth.parse(month.trim());
        } catch (DateTimeParseException e) {
            throw new BadRequestException("Month must be in yyyy-MM format, e.g. 2026-08.");
        }
    }

    private static String normalizeId(String id) {
        return (id == null || id.isBlank()) ? null : id;
    }
}
