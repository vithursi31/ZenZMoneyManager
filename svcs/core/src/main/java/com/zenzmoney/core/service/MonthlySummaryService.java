package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.TimeUtils;
import com.zenzmoney.common.domain.TransactionType;
import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.common.i18n.Msg;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.TransactionRepository;
import com.zenzmoney.core.web.dto.MonthlySummaryResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * The monthly position (§1.10, F-1.2) and the dashboard summary built on it
 * (F-1.17): {@code Σ INCOME − Σ EXPENSE} over one calendar month.
 *
 * <p>Nothing here is cached or stored. The figure is two indexed sums per request,
 * which is what makes it impossible for it to disagree with the ledger — the class
 * of bug that a materialized balance and its reconciliation flow exist to chase.
 */
@Service
public class MonthlySummaryService {

    private final TransactionRepository transactionRepository;
    private final AccountService accountService;
    private final CurrentUserService currentUser;

    public MonthlySummaryService(TransactionRepository transactionRepository,
                                 AccountService accountService,
                                 CurrentUserService currentUser) {
        this.transactionRepository = transactionRepository;
        this.accountService = accountService;
        this.currentUser = currentUser;
    }

    /**
     * The caller's position for {@code month} (ISO {@code yyyy-MM}), or the current
     * month when omitted. Any past or future month is equally computable — there is
     * no "closed" month, because no month was ever rolled up.
     *
     * <p>{@code accountId} is optional: omitted, the figure spans every account the
     * user holds, which is the position as §1.10 defines it. Supplied, it narrows to
     * that one account so the home screen's account picker and the transaction list
     * below it are answering the same question.
     */
    @Transactional(readOnly = true)
    public MonthlySummaryResponse summary(String month, String accountId) {
        User user = currentUser.requireUser();
        ZoneId zone = zoneOf(user);
        YearMonth target = parseMonth(month, zone);
        String account = accountService.requireOwnedFilter(accountId, user.getId());

        // Half-open [from, to): a transaction stamped exactly at midnight on the 1st
        // belongs to the month starting there, and to only that month.
        long from = TimeUtils.startOfMonth(target, zone);
        long to = TimeUtils.startOfMonth(target.plusMonths(1), zone);

        long income = transactionRepository.sumAmountByTypeInWindow(
                user.getId(), TransactionType.INCOME, from, to, account);
        long expenses = transactionRepository.sumAmountByTypeInWindow(
                user.getId(), TransactionType.EXPENSE, from, to, account);

        return new MonthlySummaryResponse(target.toString(), zone.getId(), from, to,
                income, expenses, income - expenses, user.getActiveCurrency(), account);
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
            throw new BadRequestException(Msg.MONTH_FORMAT);
        }
    }

    private static ZoneId zoneOf(User user) {
        return TimeUtils.zoneOrUtc(user.getTimezone());
    }
}
