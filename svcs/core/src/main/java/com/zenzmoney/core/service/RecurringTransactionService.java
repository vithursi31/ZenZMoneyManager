package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.CategoryKind;
import com.zenzmoney.common.domain.CategoryStatus;
import com.zenzmoney.common.domain.RecurringCadence;
import com.zenzmoney.common.domain.TimeUtils;
import com.zenzmoney.common.domain.TransactionType;
import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.common.exception.NotFoundException;
import com.zenzmoney.common.i18n.Msg;
import com.zenzmoney.core.entity.Account;
import com.zenzmoney.core.entity.Category;
import com.zenzmoney.core.entity.RecurringTransaction;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.AccountRepository;
import com.zenzmoney.core.repository.CategoryRepository;
import com.zenzmoney.core.repository.RecurringTransactionRepository;
import com.zenzmoney.core.repository.UserRepository;
import com.zenzmoney.core.web.dto.CreateRecurringRequest;
import com.zenzmoney.core.web.dto.RecurringCreatedResponse;
import com.zenzmoney.core.web.dto.RecurringResponse;
import com.zenzmoney.core.web.dto.TransactionResponse;
import com.zenzmoney.core.web.dto.UpcomingOccurrenceResponse;
import com.zenzmoney.core.web.dto.UpdateRecurringRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * CRUD for {@link RecurringTransaction} templates plus the generation engine (§1.8).
 * A template anchors to a user-picked date; MONTHLY/YEARLY cadences preserve the
 * anchor day-of-month across short months (a "31st" template clamps to the last day
 * of a shorter month, then returns to the 31st). {@link #runTemplate} catches up all
 * occurrences due since the last run, generating one ledger row each and advancing
 * {@code nextRunDate} — atomically per template, so a crash mid-run never duplicates.
 *
 * <p>All of that date math runs in the <em>owner's</em> timezone, the same zone the
 * monthly position is sliced in (§1.10) — otherwise a template anchored to local
 * midnight generates rows into the neighbouring month.
 */
@Service
public class RecurringTransactionService {

    private static final Logger log = LoggerFactory.getLogger(RecurringTransactionService.class);

    /** Safety bound on occurrences generated for one template in a single run (a runaway backstop). */
    static final int MAX_CATCHUP = 500;

    /** Default and maximum size of the upcoming-payments window, in days. */
    static final int DEFAULT_UPCOMING_DAYS = 3;
    static final int MAX_UPCOMING_DAYS = 90;

    /** Bound on occurrences projected for one template, so a DAILY template can't flood the list. */
    static final int MAX_UPCOMING_PER_TEMPLATE = 60;

    private final RecurringTransactionRepository recurringRepository;
    private final CategoryRepository categoryRepository;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final AccountService accountService;
    private final PayeeService payeeService;
    private final TransactionService transactionService;
    private final CurrentUserService currentUser;

    public RecurringTransactionService(RecurringTransactionRepository recurringRepository,
                                       CategoryRepository categoryRepository,
                                       AccountRepository accountRepository,
                                       UserRepository userRepository,
                                       AccountService accountService,
                                       PayeeService payeeService,
                                       TransactionService transactionService,
                                       CurrentUserService currentUser) {
        this.recurringRepository = recurringRepository;
        this.categoryRepository = categoryRepository;
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.accountService = accountService;
        this.payeeService = payeeService;
        this.transactionService = transactionService;
        this.currentUser = currentUser;
    }

    // --- template CRUD ---

    /**
     * Creates the template and, when its first run is already due, posts that one
     * occurrence immediately — a subscription added on its billing day appears in the
     * ledger with the response, instead of the user watching an empty list until the
     * next scheduler tick. Any further backlog is left to the scheduler, so the request
     * stays bounded.
     */
    @Transactional
    public RecurringCreatedResponse create(CreateRecurringRequest req) {
        User user = currentUser.requireUser();
        String userId = user.getId();
        ZoneId zone = TimeUtils.zoneOrUtc(user.getTimezone());
        validate(userId, req.getType(), req.getCategoryId(), req.getAmount());

        // One read for both: the account the template posts to is also what denominates it (§1.4).
        Account account = accountService.provision(user);

        RecurringTransaction r = new RecurringTransaction();
        r.setUserId(userId);
        r.setAccountId(account.getId());
        r.setType(req.getType());
        r.setCategoryId(req.getCategoryId());
        r.setAmount(req.getAmount());
        r.setCadence(req.getCadence());
        r.setNextRunDate(req.getNextRunDate());
        r.setAnchorDay(dayOfMonth(req.getNextRunDate(), zone));
        r.setTrialEndDate(req.getTrialEndDate());
        r.setEndDate(req.getEndDate());
        r.setActive(true);
        r.setPayeeId(payeeService.resolveOrCreate(userId, req.getPayeeName()));
        r.setNote(req.getNote());
        r.setPaymentMethod(req.getPaymentMethod());

        RecurringTransaction saved = recurringRepository.save(r);
        log.info("Recurring template created: {} {} {} {} next={} trialEnd={} method={} (template {}, user {})",
                saved.getType(), saved.getAmount(), account.getCurrency(), saved.getCadence(),
                saved.getNextRunDate(), saved.getTrialEndDate(), saved.getPaymentMethod(),
                saved.getId(), userId);

        List<TransactionResponse> posted = generate(saved, zone, account.getCurrency(), 1);
        return new RecurringCreatedResponse(RecurringResponse.of(saved, account.getCurrency()),
                posted.isEmpty() ? null : posted.get(0));
    }

    @Transactional(readOnly = true)
    public List<RecurringResponse> list(boolean includeInactive) {
        User user = currentUser.requireUser();
        Map<String, String> currencies = currencyByAccount(user.getId());
        return recurringRepository.findByUserId(user.getId()).stream()
                .filter(r -> includeInactive || r.isActive())
                .sorted(Comparator.comparingLong(RecurringTransaction::getNextRunDate))
                .map(r -> RecurringResponse.of(r, currencyOf(currencies, r, user)))
                .toList();
    }

    @Transactional(readOnly = true)
    public RecurringResponse get(String id) {
        User user = currentUser.requireUser();
        RecurringTransaction r = requireOwned(id, user.getId());
        return RecurringResponse.of(r, currencyOf(r, user));
    }

    @Transactional
    public RecurringResponse update(String id, UpdateRecurringRequest req) {
        User user = currentUser.requireUser();
        String userId = user.getId();
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
            // A reschedule re-anchors the cycle, read in the owner's zone like the original anchor.
            r.setAnchorDay(dayOfMonth(req.getNextRunDate(), TimeUtils.zoneOrUtc(user.getTimezone())));
        }
        if (req.getTrialEndDate() != null) r.setTrialEndDate(req.getTrialEndDate());
        if (req.getEndDate() != null) r.setEndDate(req.getEndDate());
        if (req.getActive() != null) r.setActive(req.getActive());
        if (req.getPayeeName() != null) r.setPayeeId(payeeService.resolveOrCreate(userId, req.getPayeeName()));
        if (req.getNote() != null) r.setNote(req.getNote());
        if (req.getPaymentMethod() != null) r.setPaymentMethod(req.getPaymentMethod());

        RecurringTransaction saved = recurringRepository.save(r);
        String currency = currencyOf(saved, user);
        log.info("Recurring template updated: {} {} {} next={} active={} method={} (template {}, user {})",
                saved.getType(), saved.getAmount(), currency, saved.getNextRunDate(),
                saved.isActive(), saved.getPaymentMethod(), saved.getId(), userId);
        return RecurringResponse.of(saved, currency);
    }

    /**
     * Deletes the template. Transactions it already generated are real ledger rows and
     * are left untouched (their {@code recurringId} becomes a dangling historical label).
     * To stop future generation without deleting, set {@code active = false} instead.
     */
    @Transactional
    public void delete(String id) {
        User user = currentUser.requireUser();
        String userId = user.getId();
        RecurringTransaction r = requireOwned(id, userId);
        String currency = currencyOf(r, user);
        recurringRepository.delete(r);
        log.info("Recurring template deleted: {} {} {} {} (template {}, user {})",
                r.getType(), r.getAmount(), currency, r.getCadence(), id, userId);
    }

    // --- upcoming payments (F-1.7 / F-1.20) ---

    /**
     * The caller's bills, renewals and salary due within the next {@code withinDays}
     * days, projected from the templates. <b>Nothing is written</b>: an occurrence here is
     * not a ledger row and is counted by no total until the scheduler posts it on its
     * due date, which is what keeps the monthly position (§1.10) a record of what has
     * happened rather than of what is expected.
     *
     * <p>The window runs to the end of the target day in the caller's timezone, so a
     * renewal on the 24th is visible on the 21st with {@code withinDays=3} whatever
     * time of day it falls at. Occurrences already due but not yet posted are included —
     * the template's {@code nextRunDate} has not advanced past them, so they exist
     * nowhere else.
     */
    @Transactional(readOnly = true)
    public List<UpcomingOccurrenceResponse> upcoming(Integer withinDays) {
        User user = currentUser.requireUser();
        ZoneId zone = TimeUtils.zoneOrUtc(user.getTimezone());
        int days = withinDays == null ? DEFAULT_UPCOMING_DAYS : withinDays;
        if (days < 1 || days > MAX_UPCOMING_DAYS) {
            throw new BadRequestException(Msg.RECURRING_UPCOMING_WINDOW_INVALID, MAX_UPCOMING_DAYS);
        }

        long now = TimeUtils.now();
        long until = TimeUtils.startOfDay(LocalDate.ofInstant(Instant.ofEpochMilli(now), zone)
                .plusDays(days + 1L), zone);

        Map<String, String> currencies = currencyByAccount(user.getId());
        List<UpcomingOccurrenceResponse> out = new ArrayList<>();
        for (RecurringTransaction r : recurringRepository.findByUserIdAndActiveTrue(user.getId())) {
            project(r, currencyOf(currencies, r, user), now, until, zone, out);
        }
        out.sort(Comparator.comparingLong(UpcomingOccurrenceResponse::getDueDate));
        return out;
    }

    /** Walks one template's schedule into {@code out} until it leaves the window. */
    private void project(RecurringTransaction r, String currency, long now, long until, ZoneId zone,
                         List<UpcomingOccurrenceResponse> out) {
        boolean trialEnding = r.getTrialEndDate() != null
                && r.getTrialEndDate() >= now && r.getTrialEndDate() < until;
        long cursor = r.getNextRunDate();
        int projected = 0;
        while (cursor < until
                && (r.getEndDate() == null || cursor <= r.getEndDate())
                && projected < MAX_UPCOMING_PER_TEMPLATE) {
            out.add(UpcomingOccurrenceResponse.of(r, currency, cursor, cursor <= now, trialEnding));
            cursor = advance(cursor, r.getCadence(), r.getAnchorDay(), zone);
            projected++;
        }
        if (projected >= MAX_UPCOMING_PER_TEMPLATE) {
            log.debug("Upcoming projection for template {} capped at {} occurrences",
                    r.getId(), MAX_UPCOMING_PER_TEMPLATE);
        }
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
        User owner = userRepository.findById(r.getUserId()).orElse(null);
        ZoneId zone = TimeUtils.zoneOrUtc(owner == null ? null : owner.getTimezone());
        return generate(r, zone, requireCurrency(r), MAX_CATCHUP).size();
    }

    /**
     * Posts up to {@code max} due occurrences of {@code r}, advancing {@code nextRunDate}
     * past each and retiring the template once the schedule runs out. Shared by the
     * scheduler (full catch-up) and create (the one occurrence due right now).
     */
    private List<TransactionResponse> generate(RecurringTransaction r, ZoneId zone,
                                              String currency, int max) {
        long now = TimeUtils.now();
        List<TransactionResponse> posted = new ArrayList<>();
        while (r.getNextRunDate() <= now
                && (r.getEndDate() == null || r.getNextRunDate() <= r.getEndDate())
                && posted.size() < max) {
            long runDate = r.getNextRunDate();
            posted.add(transactionService.generateFromRecurring(r, runDate, currency));
            r.setNextRunDate(advance(runDate, r.getCadence(), r.getAnchorDay(), zone));
        }
        boolean retired = r.isActive() && r.getEndDate() != null && r.getNextRunDate() > r.getEndDate();
        if (retired) {
            r.setActive(false);
        }
        if (posted.size() >= max && r.getNextRunDate() <= now) {
            log.warn("Recurring template {} hit the per-run cap ({}); the rest generate on the next run",
                    r.getId(), max);
        }
        // Nothing due and nothing retired means nothing changed — a template that is simply
        // not due yet should not produce a write on every pass.
        if (!posted.isEmpty() || retired) {
            recurringRepository.save(r);
        }
        return posted;
    }

    // --- date math (§1.8): anchor-preserving advance, in the owner's timezone ---

    /**
     * The next run after {@code fromMillis}, read in {@code zone}. DAILY/WEEKLY add a
     * calendar day or week — so a template keeps its local time of day across a DST
     * shift. MONTHLY/YEARLY land on {@code anchorDay} of the target month, clamped to
     * that month's length, so a 31st anchor gives Feb 28/29 then March 31 rather than
     * drifting to the 28th permanently.
     */
    static long advance(long fromMillis, RecurringCadence cadence, int anchorDay, ZoneId zone) {
        ZonedDateTime from = Instant.ofEpochMilli(fromMillis).atZone(zone);
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

    private static int dayOfMonth(long millis, ZoneId zone) {
        return Instant.ofEpochMilli(millis).atZone(zone).getDayOfMonth();
    }

    // --- internals ---

    /**
     * The currency a template is denominated in: the one on the account it posts to
     * (§1.4). Not stored on the template — the account is the only holder, so a
     * re-denominated account can never disagree with a stale copy.
     *
     * <p>On the write path a missing account is a hard stop rather than a guess: the
     * scheduler isolates and logs the failing template, which is a better outcome than
     * posting money in a currency nobody chose.
     */
    private String requireCurrency(RecurringTransaction r) {
        return accountRepository.findByIdAndUserId(r.getAccountId(), r.getUserId())
                .map(Account::getCurrency)
                .orElseThrow(() -> new NotFoundException(Msg.ACCOUNT_NOT_FOUND));
    }

    private String currencyOf(RecurringTransaction r, User user) {
        return accountRepository.findByIdAndUserId(r.getAccountId(), user.getId())
                .map(Account::getCurrency)
                .orElse(user.getActiveCurrency());
    }

    /** One account read for a whole listing, rather than one per template. */
    private Map<String, String> currencyByAccount(String userId) {
        return accountRepository.findByUserId(userId).stream()
                .collect(Collectors.toMap(Account::getId, Account::getCurrency, (a, b) -> a));
    }

    /** A read falls back to the user's active currency, so one odd row cannot 404 a whole list. */
    private static String currencyOf(Map<String, String> currencies, RecurringTransaction r, User user) {
        return currencies.getOrDefault(r.getAccountId(), user.getActiveCurrency());
    }

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
