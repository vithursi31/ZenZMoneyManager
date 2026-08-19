package com.zenzmoney.core.service;

import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.core.entity.Account;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.UserRepository;
import com.zenzmoney.core.util.SupportedCurrencies;
import com.zenzmoney.core.web.dto.CurrencyResponse;
import com.zenzmoney.core.web.dto.OnboardingRequest;
import com.zenzmoney.core.web.dto.OnboardingResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

/**
 * First-run setup (F-1.27): the user picks a currency and language, and everything
 * else is provisioned for them — the single account (F-1.1) and the default
 * category set (F-1.5). No starting balance is requested, because there is no
 * balance to start (§1.10).
 *
 * <p>This is where a currency stops being provisional rather than where it first
 * appears: registration already seeded one from the locale the client reported
 * ({@link SignupDefaults}), so this screen confirms or corrects it. Until the user
 * gets here {@code onboarded} stays false and the guess remains replaceable;
 * afterwards the currency is frozen (§0.3).
 *
 * <p>Idempotent end to end: re-running it updates the preferences and leaves the
 * existing account and categories alone. A user who reinstalls and re-onboards
 * does not get a second account or duplicate categories.
 */
@Service
public class OnboardingService {

    private static final Logger log = LoggerFactory.getLogger(OnboardingService.class);

    private final UserRepository userRepository;
    private final AccountService accountService;
    private final CategoryService categoryService;
    private final CurrentUserService currentUser;

    public OnboardingService(UserRepository userRepository,
                             AccountService accountService,
                             CategoryService categoryService,
                             CurrentUserService currentUser) {
        this.userRepository = userRepository;
        this.accountService = accountService;
        this.categoryService = categoryService;
        this.currentUser = currentUser;
    }

    @Transactional
    public OnboardingResponse complete(OnboardingRequest req) {
        User user = currentUser.requireUser();
        String currency = requireIsoCurrency(req.getCurrency());

        String previous = user.getActiveCurrency();
        boolean changing = previous != null && !previous.isBlank() && !previous.equals(currency);
        if (changing && user.isOnboarded()) {
            // Existing amounts are minor units denominated in the old currency, and
            // re-denominating them is a product decision, not a side effect of a
            // settings call (§0.3). Blocked until the switch policy exists.
            throw new BadRequestException(
                    "Changing your active currency is not supported yet; it is currently " + previous + ".");
        }
        if (changing) {
            // Still provisional: the previous value was guessed from the signup
            // locale, not chosen, so correcting it here is the point of this screen.
            // Refused by AccountService if the guess already has amounts behind it.
            accountService.redenominate(user, currency);
        }

        user.setActiveCurrency(currency);
        if (req.getLanguage() != null && !req.getLanguage().isBlank()) {
            user.setLanguage(req.getLanguage().trim());
        }
        if (req.getTimezone() != null && !req.getTimezone().isBlank()) {
            user.setTimezone(requireZone(req.getTimezone()));
        }
        user.setOnboarded(true);
        userRepository.save(user);

        Account account = accountService.provision(user);
        int categories = categoryService.seedDefaults().size();

        log.info("Onboarding completed: currency={} (was {}) language={} timezone={} categories={} (account {}, user {})",
                currency, previous, user.getLanguage(), user.getTimezone(), categories,
                account.getId(), user.getId());
        return new OnboardingResponse(account.getId(), currency, user.getLanguage(),
                user.getTimezone(), categories);
    }

    /**
     * Every selectable currency: the JDK's registry (for display name and minor-unit
     * scale) narrowed to {@link SupportedCurrencies}, the maintained allowlist that
     * decides what's actually offered — editing that file, not this code, is how a
     * currency gets added or removed from the picker.
     */
    public List<CurrencyResponse> listCurrencies() {
        return Currency.getAvailableCurrencies().stream()
                .filter(c -> SupportedCurrencies.contains(c.getCurrencyCode()))
                .map(c -> new CurrencyResponse(c.getCurrencyCode(),
                        c.getDisplayName(Locale.ENGLISH), c.getDefaultFractionDigits()))
                .sorted(Comparator.comparing(CurrencyResponse::getCode))
                .toList();
    }

    /**
     * ISO-4217 and on the {@link SupportedCurrencies} allowlist, or nothing. A code
     * the JDK still recognizes but the allowlist doesn't carry (e.g. a retired
     * currency) is rejected the same way an unknown code is — accepting it here
     * would be a backdoor around the picker in {@link #listCurrencies}.
     */
    private static String requireIsoCurrency(String raw) {
        String code = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        try {
            Currency currency = Currency.getInstance(code);
            if (!SupportedCurrencies.contains(currency.getCurrencyCode())) {
                throw new BadRequestException("Unknown currency code: " + raw);
            }
            return currency.getCurrencyCode();
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown currency code: " + raw);
        }
    }

    /** An IANA zone id — it decides where the user's month boundaries fall (§1.10). */
    private static String requireZone(String raw) {
        try {
            return ZoneId.of(raw.trim()).getId();
        } catch (DateTimeException e) {
            throw new BadRequestException("Unknown timezone: " + raw);
        }
    }
}
