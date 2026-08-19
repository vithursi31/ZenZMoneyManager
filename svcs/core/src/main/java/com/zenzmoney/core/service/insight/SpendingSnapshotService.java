package com.zenzmoney.core.service.insight;

import com.zenzmoney.common.domain.TimeUtils;
import com.zenzmoney.common.domain.TransactionType;
import com.zenzmoney.core.entity.Category;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.CategoryRepository;
import com.zenzmoney.core.repository.CategoryTotal;
import com.zenzmoney.core.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds the figures the assistant answers from (F-1.16, domain §3.6).
 *
 * <p><b>The numbers are computed here, never by the model.</b> A language model
 * asked to add up a ledger will produce a plausible total and be wrong, and a wrong
 * figure about someone's own money is worse than no answer. So the arithmetic is
 * SQL aggregates over the same half-open month windows the monthly position uses
 * (§1.10), and the model's only job is to turn them into a sentence.
 */
@Service
public class SpendingSnapshotService {

    /**
     * The month in progress plus the last complete one. Two is what a question needs:
     * "how much did I spend last month" reads one, "am I spending more" compares them.
     */
    private static final int MONTHS = 2;

    /**
     * Enough to name where the money went without turning the prompt into a ledger
     * dump. The tail is a long list of small amounts that no advice would act on.
     */
    private static final int MAX_CATEGORIES = 8;

    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;

    public SpendingSnapshotService(TransactionRepository transactionRepository,
                                   CategoryRepository categoryRepository) {
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
    }

    /** The caller's recent months, scoped to them and resolved in their own timezone. */
    @Transactional(readOnly = true)
    public SpendingSnapshot snapshotFor(User user) {
        ZoneId zone = TimeUtils.zoneOrUtc(user.getTimezone());
        YearMonth thisMonth = TimeUtils.monthOf(TimeUtils.now(), zone);

        Map<String, String> categoryNames = categoryRepository.findByUserId(user.getId()).stream()
                .collect(Collectors.toMap(Category::getId, Category::getName, (first, second) -> first));

        List<SpendingSnapshot.MonthSpend> months = new ArrayList<>(MONTHS);
        for (int back = 0; back < MONTHS; back++) {
            months.add(monthSpend(user.getId(), thisMonth.minusMonths(back), zone, categoryNames));
        }
        return new SpendingSnapshot(user.getActiveCurrency(), zone.getId(), months);
    }

    private SpendingSnapshot.MonthSpend monthSpend(String userId, YearMonth month, ZoneId zone,
                                                   Map<String, String> categoryNames) {
        // Half-open [from, to), the same boundary the position uses — so a figure quoted
        // in an answer and the one on the dashboard are the same number.
        long from = TimeUtils.startOfMonth(month, zone);
        long to = TimeUtils.startOfMonth(month.plusMonths(1), zone);

        long income = transactionRepository.sumAmountByTypeInWindow(userId, TransactionType.INCOME, from, to);
        long expenses = transactionRepository.sumAmountByTypeInWindow(userId, TransactionType.EXPENSE, from, to);

        List<SpendingSnapshot.CategorySpend> categories =
                transactionRepository.sumExpenseByCategoryInWindowGrouped(userId, from, to).stream()
                        .limit(MAX_CATEGORIES)
                        .map(total -> new SpendingSnapshot.CategorySpend(
                                total.getCategoryId(),
                                // A category deleted since the transaction was recorded leaves the
                                // spend real and its label gone; naming it "Uncategorised" keeps the
                                // month's totals adding up instead of silently dropping the row.
                                categoryNames.getOrDefault(total.getCategoryId(), "Uncategorised"),
                                total.getAmount()))
                        .toList();

        return new SpendingSnapshot.MonthSpend(month.toString(), income, expenses, categories);
    }
}
