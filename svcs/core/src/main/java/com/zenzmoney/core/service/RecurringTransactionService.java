package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.CategoryKind;
import com.zenzmoney.common.domain.CategoryStatus;
import com.zenzmoney.common.domain.RecurringCadence;
import com.zenzmoney.common.domain.TimeUtils;
import com.zenzmoney.common.domain.TransactionType;
import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.common.exception.NotFoundException;
import com.zenzmoney.common.i18n.Msg;
import com.zenzmoney.core.entity.Category;
import com.zenzmoney.core.entity.RecurringTransaction;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.CategoryRepository;
import com.zenzmoney.core.repository.RecurringTransactionRepository;
import com.zenzmoney.core.web.dto.CreateRecurringRequest;
import com.zenzmoney.core.web.dto.RecurringResponse;
import com.zenzmoney.core.web.dto.UpdateRecurringRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * CRUD for {@link RecurringTransaction} templates plus the generation engine (§1.8).
 * A template anchors to a user-picked date; MONTHLY/YEARLY cadences preserve the
 * anchor day-of-month across short months (a "31st" template clamps to the last day
 * of a shorter month, then returns to the 31st). {@link #runTemplate} catches up all
 * occurrences due since the last run, generating one ledger row each and advancing
 * {@code nextRunDate} — atomically per template, so a crash mid-run never duplicates.
 */
@Service
public class RecurringTransactionService {

    private static final Logger log = LoggerFactory.getLogger(RecurringTransactionService.class);

    /** Safety bound on occurrences generated for one template in a single run (a runaway backstop). */
    static final int MAX_CATCHUP = 500;

    private final RecurringTransactionRepository recurringRepository;
    private final CategoryRepository categoryRepository;
    private final AccountService accountService;
    private final PayeeService payeeService;
    private final TransactionService transactionService;
    private final CurrentUserService currentUser;

    public RecurringTransactionService(RecurringTransactionRepository recurringRepository,
                                       CategoryRepository categoryRepository,
                                       AccountService accountService,
                                       PayeeService payeeService,
                                       TransactionService transactionService,
                                       CurrentUserService currentUser) {
        this.recurringRepository = recurringRepository;
        this.categoryRepository = categoryRepository;
        this.accountService = accountService;
        this.payeeService = payeeService;
        this.transactionService = transactionService;
        this.currentUser = currentUser;
    }

    // --- template CRUD ---

    @Transactional
    public RecurringResponse create(CreateRecurringRequest req) {
        User user = currentUser.requireUser();
        String userId = user.getId();
        validate(userId, req.getType(), req.getCategoryId(), req.getAmount());

        RecurringTransaction r = new RecurringTransaction();
        r.setUserId(userId);
        r.setAccountId(accountService.requireAccountId(user));
        r.setType(req.getType());
        r.setCategoryId(req.getCategoryId());
        r.setAmount(req.getAmount());
        r.setCurrency(user.getActiveCurrency());
        r.setCadence(req.getCadence());
        r.setNextRunDate(req.getNextRunDate());
        r.setAnchorDay(dayOfMonth(req.getNextRunDate()));
        r.setTrialEndDate(req.getTrialEndDate());
        r.setEndDate(req.getEndDate());
        r.setActive(true);
        r.setPayeeId(payeeService.resolveOrCreate(userId, req.getPayeeName()));
        r.setNote(req.getNote());

        RecurringTransaction saved = recurringRepository.save(r);
        log.info("Recurring template created: {} {} {} {} next={} trialEnd={} (template {}, user {})",
                saved.getType(), saved.getAmount(), saved.getCurrency(), saved.getCadence(),
                saved.getNextRunDate(), saved.getTrialEndDate(), saved.getId(), userId);
        return RecurringResponse.of(saved);
    }

    @Transactional(readOnly = true)
    public List<RecurringResponse> list(boolean includeInactive) {
        String userId = currentUser.requireUserId();
        return recurringRepository.findByUserId(userId).stream()
                .filter(r -> includeInactive || r.isActive())
                .sorted(Comparator.comparingLong(RecurringTransaction::getNextRunDate))
                .map(RecurringResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public RecurringResponse get(String id) {
        return RecurringResponse.of(requireOwned(id, currentUser.requireUserId()));
    }

    @Transactional
    public RecurringResponse update(String id, UpdateRecurringRequest req) {
        String userId = currentUser.requireUserId();
        RecurringTransaction r = requireOwned(id, userId);
        if (req.getAmount() != null) {
            if (req.getAmount() <= 0) {
                throw new BadRequestException(Msg.AMOUNT_NOT_POSITIVE);
            }
            r.setAmount(req.getAmount());
        }
        if (req.getNextRunDate() != null) {
            if (req.getNextRunDate() <= 0) {
                throw new BadRequestException(Msg.RECURRING_NEXT_RUN_INVALID);
            }
            r.setNextRunDate(req.getNextRunDate());
            r.setAnchorDay(dayOfMonth(req.getNextRunDate()));   // reschedule re-anchors the cycle
        }
        if (req.getTrialEndDate() != null) r.setTrialEndDate(req.getTrialEndDate());
        if (req.getEndDate() != null) r.setEndDate(req.getEndDate());
        if (req.getActive() != null) r.setActive(req.getActive());
        if (req.getPayeeName() != null) r.setPayeeId(payeeService.resolveOrCreate(userId, req.getPayeeName()));
        if (req.getNote() != null) r.setNote(req.getNote());

        RecurringTransaction saved = recurringRepository.save(r);
        log.info("Recurring template updated: {} {} {} next={} active={} (template {}, user {})",
                saved.getType(), saved.getAmount(), saved.getCurrency(),
                saved.getNextRunDate(), saved.isActive(), saved.getId(), userId);
        return RecurringResponse.of(saved);
    }

    /**
     * Deletes the template. Transactions it already generated are real ledger rows and
     * are left untouched (their {@code recurringId} becomes a dangling historical label).
     * To stop future generation without deleting, set {@code active = false} instead.
     */
    @Transactional
    public void delete(String id) {
        String userId = currentUser.requireUserId();
        RecurringTransaction r = requireOwned(id, userId);
        recurringRepository.delete(r);
        log.info("Recurring template deleted: {} {} {} {} (template {}, user {})",
                r.getType(), r.getAmount(), r.getCurrency(), r.getCadence(), id, userId);
    }

    // --- generation engine (driven by the scheduler) ---

    /** Ids of active templates whose next run is due now — the scheduler's work list. */
    @Transactional(readOnly = true)
    public List<String> dueTemplateIds() {
        return recurringRepository.findByActiveTrueAndNextRunDateLessThanEqual(TimeUtils.now()).stream()
                .map(RecurringTransaction::getId)
                .toList();
    }

    /**
     * Generates every occurrence due for one template up to now (catch-up), advancing
     * {@code nextRunDate} after each, and deactivates the template once it passes
     * {@code endDate}. One DB transaction, so all generated rows and the advanced
     * {@code nextRunDate} commit together. Returns how many rows were generated.
     */
    @Transactional
    public int runTemplate(String id) {
        RecurringTransaction r = recurringRepository.findById(id).orElse(null);
        if (r == null || !r.isActive()) {
            return 0;
        }
        long now = TimeUtils.now();
        int count = 0;
        while (r.getNextRunDate() <= now
                && (r.getEndDate() == null || r.getNextRunDate() <= r.getEndDate())
                && count < MAX_CATCHUP) {
            long runDate = r.getNextRunDate();
            transactionService.generateFromRecurring(r, runDate);
            r.setNextRunDate(advance(runDate, r.getCadence(), r.getAnchorDay()));
            count++;
        }
        if (r.getEndDate() != null && r.getNextRunDate() > r.getEndDate()) {
            r.setActive(false);
        }
        if (count >= MAX_CATCHUP && r.getNextRunDate() <= now) {
            log.warn("Recurring template {} hit the per-run catch-up cap ({}); the rest generate on the next run",
                    id, MAX_CATCHUP);
        }
        recurringRepository.save(r);
        return count;
    }

    // --- date math (§1.8): anchor-preserving advance ---

    /**
     * The next run after {@code fromMillis}. DAILY/WEEKLY add a fixed span; MONTHLY/YEARLY
     * land on {@code anchorDay} of the target month, clamped to that month's length — so a
     * 31st anchor gives Feb 28/29 then March 31, never drifting to the 28th permanently.
     */
    static long advance(long fromMillis, RecurringCadence cadence, int anchorDay) {
        ZonedDateTime from = Instant.ofEpochMilli(fromMillis).atZone(ZoneOffset.UTC);
        return switch (cadence) {
            case DAILY -> from.plusDays(1).toInstant().toEpochMilli();
            case WEEKLY -> from.plusWeeks(1).toInstant().toEpochMilli();
            case MONTHLY -> atAnchor(from.withDayOfMonth(1).plusMonths(1), anchorDay);
            case YEARLY -> atAnchor(from.withDayOfMonth(1).plusYears(1), anchorDay);
        };
    }

    /** Sets {@code firstOfTargetMonth} to the anchor day, clamped to the month's length. */
    private static long atAnchor(ZonedDateTime firstOfTargetMonth, int anchorDay) {
        int day = Math.min(anchorDay, firstOfTargetMonth.toLocalDate().lengthOfMonth());
        return firstOfTargetMonth.withDayOfMonth(day).toInstant().toEpochMilli();
    }

    private static int dayOfMonth(long millis) {
        return Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).getDayOfMonth();
    }

    // --- internals ---

    /**
     * Validates the template's structural rules (§1.6) the same way a transaction is,
     * so a template that could never generate a valid row is rejected at create time
     * rather than failing nightly in the scheduler.
     */
    private void validate(String userId, TransactionType type, String categoryId, long amount) {
        if (amount <= 0) {
            throw new BadRequestException(Msg.AMOUNT_NOT_POSITIVE);
        }
        Category category = categoryRepository.findByIdAndUserIdAndStatus(categoryId, userId, CategoryStatus.ACTIVE)
                .orElseThrow(() -> new NotFoundException(Msg.CATEGORY_NOT_FOUND));
        CategoryKind expected = type == TransactionType.INCOME ? CategoryKind.INCOME : CategoryKind.EXPENSE;
        if (category.getKind() != expected) {
            throw new BadRequestException(Msg.CATEGORY_KIND_MISMATCH);
        }
    }

    private RecurringTransaction requireOwned(String id, String userId) {
        return recurringRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new NotFoundException(Msg.RECURRING_NOT_FOUND));
    }
}
