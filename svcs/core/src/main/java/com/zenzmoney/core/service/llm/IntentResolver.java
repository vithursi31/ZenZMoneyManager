package com.zenzmoney.core.service.llm;

import com.zenzmoney.common.domain.AccountStatus;
import com.zenzmoney.common.domain.CategoryKind;
import com.zenzmoney.common.domain.IntentType;
import com.zenzmoney.common.domain.TimeUtils;
import com.zenzmoney.common.domain.TransactionType;
import com.zenzmoney.core.entity.Account;
import com.zenzmoney.core.entity.Category;
import com.zenzmoney.core.entity.ParsedIntent;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.AccountRepository;
import com.zenzmoney.core.repository.CategoryRepository;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the model's reading of a message into a draft the ledger could accept
 * (chat entry plan §5.4). Everything here is deterministic: the same extraction
 * plus the same user state always produces the same draft.
 *
 * <p>That determinism is the point of the split. The model is good at reading
 * "fifteen fifty at Keells for tea things" out of a sentence and bad at knowing
 * today's date, the user's currency, or which of their categories exists. So it
 * hands over language and this class supplies the data — amount text to minor
 * units, date phrase to an instant in the user's timezone, category label to a
 * real id — where each step is testable and auditable.
 */
@Service
public class IntentResolver {

    /** Digits, optionally one dot. Anything else the model wrote is not an amount. */
    private static final Pattern AMOUNT = Pattern.compile("^\\d{1,15}(\\.\\d{1,6})?$");

    private static final Pattern DAYS_AGO = Pattern.compile("(\\d{1,3})\\s+days?\\s+ago");

    /** Currencies with no minor unit still need a divisor; default to 2 decimals. */
    private static final int DEFAULT_FRACTION_DIGITS = 2;

    /**
     * Keyword to category-name fragments (F-1.9b). Deliberately small: it covers
     * the words a capture message actually uses, and anything it misses simply
     * leaves the category unresolved for the user to pick.
     */
    private static final Map<String, List<String>> SYNONYMS = Map.ofEntries(
            Map.entry("food", List.of("food", "drink", "dining", "restaurant")),
            Map.entry("meal", List.of("food", "drink", "dining", "restaurant")),
            Map.entry("lunch", List.of("food", "drink", "dining", "restaurant")),
            Map.entry("dinner", List.of("food", "drink", "dining", "restaurant")),
            Map.entry("breakfast", List.of("food", "drink", "dining", "restaurant")),
            Map.entry("burger", List.of("food", "drink", "dining", "restaurant")),
            Map.entry("pizza", List.of("food", "drink", "dining", "restaurant")),
            Map.entry("coffee", List.of("food", "drink", "dining", "cafe")),
            Map.entry("tea", List.of("food", "drink", "dining", "cafe")),
            Map.entry("snack", List.of("food", "drink", "dining")),
            Map.entry("restaurant", List.of("food", "drink", "dining", "restaurant")),
            Map.entry("grocery", List.of("grocer", "supermarket", "food")),
            Map.entry("groceries", List.of("grocer", "supermarket", "food")),
            Map.entry("supermarket", List.of("grocer", "supermarket", "food")),
            Map.entry("transport", List.of("transport", "travel", "fuel", "car")),
            Map.entry("uber", List.of("transport", "travel", "taxi", "car")),
            Map.entry("taxi", List.of("transport", "travel", "taxi", "car")),
            Map.entry("bus", List.of("transport", "travel")),
            Map.entry("train", List.of("transport", "travel")),
            Map.entry("fuel", List.of("fuel", "transport", "car")),
            Map.entry("petrol", List.of("fuel", "transport", "car")),
            Map.entry("rent", List.of("rent", "housing", "home", "bill")),
            Map.entry("electricity", List.of("utilit", "bill", "electric")),
            Map.entry("internet", List.of("utilit", "bill", "internet")),
            Map.entry("bill", List.of("bill", "utilit")),
            Map.entry("medicine", List.of("health", "medical", "pharmacy")),
            Map.entry("doctor", List.of("health", "medical")),
            Map.entry("pharmacy", List.of("health", "medical", "pharmacy")),
            Map.entry("clothes", List.of("shopping", "clothes", "apparel")),
            Map.entry("shopping", List.of("shopping", "clothes")),
            Map.entry("movie", List.of("entertain", "leisure", "fun")),
            Map.entry("entertainment", List.of("entertain", "leisure", "fun")),
            Map.entry("salary", List.of("salary", "wage", "pay")),
            Map.entry("wage", List.of("salary", "wage", "pay")),
            Map.entry("payroll", List.of("salary", "wage", "pay")),
            Map.entry("bonus", List.of("bonus", "salary", "income")),
            Map.entry("gift", List.of("gift")),
            Map.entry("interest", List.of("interest", "investment", "income")),
            Map.entry("dividend", List.of("dividend", "investment", "income"))
    );

    /**
     * Words that say which way the money went, in the user's own message. Measured
     * against qwen2.5:1.5b, {@code txnType} is the field the model is least stable
     * on — it answered EXPENSE and INCOME for the same "paid 250 for uber
     * yesterday" on consecutive runs. A verb the user actually typed is a stronger
     * signal than that field, so it wins (see {@link #resolveType}).
     *
     * <p>Listed per language the app supports (F-1.24), because the guard is only
     * worth having in the language the user actually typed: qwen2.5 flipped
     * "pagué 250 por uber ayer" to INCOME exactly as it flipped the English one, and
     * an English-only list leaves those users unprotected. Accented and unaccented
     * spellings are both listed — {@link #normalize} lowercases but does not strip
     * accents, and users type both.
     */
    private static final List<String> EXPENSE_WORDS = List.of(
            // en
            "spent", "spend", "paid", "pay", "bought", "buy", "purchased", "purchase",
            "cost", "withdrew", "withdraw", "donated",
            // fr
            "dépensé", "depense", "dépense", "payé", "paye", "acheté", "achete", "retiré", "retire",
            // es
            "gasté", "gaste", "pagué", "pague", "compré", "compre", "gastado");

    private static final List<String> INCOME_WORDS = List.of(
            // en
            "earned", "earn", "received", "receive", "credited", "deposited", "refunded",
            "refund", "salary", "bonus", "dividend", "income",
            // fr
            "reçu", "recu", "gagné", "gagne", "salaire", "remboursé", "rembourse",
            // es
            "recibí", "recibi", "gané", "gane", "sueldo", "salario", "ingreso", "reembolso");

    private final CategoryRepository categoryRepository;
    private final AccountRepository accountRepository;

    public IntentResolver(CategoryRepository categoryRepository, AccountRepository accountRepository) {
        this.categoryRepository = categoryRepository;
        this.accountRepository = accountRepository;
    }

    /**
     * Builds the draft. Never throws — anything that can't be resolved is recorded
     * in {@link ParsedIntent#getMissingFields()} so the flow can ask about it
     * instead of failing.
     *
     * @param message the user's original text, used as a second signal for category
     *                matching when the model's label is a worse fit than a word the
     *                user actually typed.
     */
    public ParsedIntent resolve(User user, String message, LlmExtraction extraction) {
        ParsedIntent draft = new ParsedIntent();
        draft.setIntent(extraction.getIntent());
        draft.setTxnType(extraction.getTxnType());
        draft.setCategoryGuess(trimToNull(extraction.getCategoryGuess()));
        draft.setNote(trimToNull(extraction.getNote()));
        draft.setPayeeName(trimToNull(extraction.getPayee()));
        draft.setConfidence(extraction.getConfidence());

        if (extraction.getIntent() != IntentType.CREATE_TRANSACTION) {
            // Not a capture. Mark it incomplete so no later code path can confirm it.
            draft.getMissingFields().add("intent");
            return draft;
        }

        Account account = defaultAccount(user.getId());
        if (account == null) {
            draft.getMissingFields().add("account");
        } else {
            draft.setAccountId(account.getId());
        }

        String currency = user.getActiveCurrency() != null && !user.getActiveCurrency().isBlank()
                ? user.getActiveCurrency()
                : (account != null ? account.getCurrency() : null);
        draft.setCurrency(currency);

        Long amount = currency == null ? null : toMinorUnits(extraction.getAmountRaw(), currency);
        if (amount == null || amount <= 0) {
            draft.getMissingFields().add("amount");
        } else {
            draft.setAmountMinor(amount);
        }

        draft.setTxnDate(resolveDate(extraction.getDateExpr(), zoneOf(user), TimeUtils.now()));

        List<Category> categories = categoryRepository.findByUserId(user.getId());
        TransactionType type = resolveType(extraction.getTxnType(), message);

        // A guess whose kind contradicts the direction means one of the two is wrong,
        // and the draft cannot say which. Ask about the direction rather than silently
        // discarding a plausible category and asking the less useful "which category?".
        if (type != null && type != TransactionType.TRANSFER
                && contradictsGuessKind(categories, type, draft.getCategoryGuess())) {
            type = null;
        }
        draft.setTxnType(type);

        if (type == null) {
            draft.getMissingFields().add("type");
        } else if (type == TransactionType.TRANSFER) {
            // Out of scope for chat (§2): a transfer needs two accounts resolved.
            draft.getMissingFields().add("transfer");
        } else {
            String categoryId = resolveCategory(categories, type, draft.getCategoryGuess(), message);
            if (categoryId == null) {
                draft.getMissingFields().add("category");
            } else {
                draft.setCategoryId(categoryId);
            }
        }
        return draft;
    }

    // --- direction ---

    /**
     * Decides income-vs-expense, preferring a direction word the user typed over the
     * model's {@code txnType}. A message carrying words from <em>both</em> directions
     * ("paid salary 3000") is genuinely ambiguous, so the model's answer stands — an
     * override is only worth having where the user's own wording is unambiguous.
     *
     * <p>Also fills the direction the model left null, which turns a clarifying
     * question the user can already see the answer to into no question at all.
     */
    static TransactionType resolveType(TransactionType modelType, String message) {
        if (modelType == TransactionType.TRANSFER) {
            // "paid 500 into my savings" carries an expense verb but is not an expense.
            // A direction word cannot express "between the user's own accounts", so
            // overriding TRANSFER would lose information rather than correct it.
            return TransactionType.TRANSFER;
        }
        String text = normalize(message);
        boolean out = EXPENSE_WORDS.stream().anyMatch(word -> containsWord(text, word));
        boolean in = INCOME_WORDS.stream().anyMatch(word -> containsWord(text, word));

        if (out == in) {
            // Neither direction named, or both: nothing better than what the model said.
            return modelType;
        }
        return out ? TransactionType.EXPENSE : TransactionType.INCOME;
    }

    /**
     * True when the model's category label names one of the user's categories whose
     * kind is the opposite of {@code type} — e.g. txnType INCOME with a guess of
     * "Transport". A label matching nothing the user owns is not a contradiction,
     * just an unusable guess.
     */
    private static boolean contradictsGuessKind(List<Category> categories, TransactionType type, String guess) {
        String label = normalize(guess);
        if (label.isEmpty()) {
            return false;
        }
        CategoryKind wanted = kindFor(type);
        return categories.stream()
                .filter(c -> normalize(c.getName()).equals(label))
                .findFirst()
                .map(c -> c.getKind() != wanted)
                .orElse(false);
    }

    // --- amount ---

    /**
     * Converts the model's amount text to minor units of {@code currencyCode}.
     *
     * <p>The conversion is integer-only and never goes through a {@code double}:
     * the text is split at the dot and reassembled with {@code long} arithmetic,
     * so "0.1" is exactly 10 and not 9.999… rounded. Half-up on the first dropped
     * digit. Returns null when the text isn't a plain decimal number.
     */
    static Long toMinorUnits(String rawAmount, String currencyCode) {
        if (rawAmount == null) {
            return null;
        }
        String value = rawAmount.trim();
        if (!AMOUNT.matcher(value).matches()) {
            return null;
        }
        int digits = fractionDigits(currencyCode);

        int dot = value.indexOf('.');
        String whole = dot < 0 ? value : value.substring(0, dot);
        String fraction = dot < 0 ? "" : value.substring(dot + 1);

        // One digit beyond the currency's precision, to round on.
        StringBuilder padded = new StringBuilder(fraction);
        while (padded.length() < digits + 1) {
            padded.append('0');
        }
        String kept = padded.substring(0, digits);
        boolean roundUp = padded.charAt(digits) >= '5';

        long minor = Long.parseLong(whole) * pow10(digits) + (kept.isEmpty() ? 0L : Long.parseLong(kept));
        return roundUp ? minor + 1 : minor;
    }

    private static int fractionDigits(String currencyCode) {
        try {
            int digits = Currency.getInstance(currencyCode).getDefaultFractionDigits();
            return digits < 0 ? DEFAULT_FRACTION_DIGITS : digits;
        } catch (IllegalArgumentException | NullPointerException e) {
            return DEFAULT_FRACTION_DIGITS;
        }
    }

    private static long pow10(int exponent) {
        long result = 1;
        for (int i = 0; i < exponent; i++) {
            result *= 10;
        }
        return result;
    }

    // --- date ---

    /**
     * Resolves the model's date <em>phrase</em> against the server clock and the
     * user's timezone (§6). The model never sends an absolute date because it has
     * no clock; this is where "yesterday" becomes an instant.
     *
     * <p>"today" resolves to the current moment rather than midnight, so a
     * transaction captured now sorts correctly against one captured an hour ago.
     * Any other day resolves to that day's start in the user's zone — there is no
     * time of day to preserve.
     */
    static long resolveDate(String dateExpr, ZoneId zone, long nowMillis) {
        if (dateExpr == null || dateExpr.isBlank()) {
            return nowMillis;
        }
        String expr = dateExpr.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        LocalDate today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate();

        if (expr.contains("today") || expr.contains("now") || expr.contains("this morning")
                || expr.contains("this afternoon") || expr.contains("this evening") || expr.contains("tonight")) {
            return nowMillis;
        }
        if (expr.contains("day before yesterday")) {
            return startOfDay(today.minusDays(2), zone);
        }
        if (expr.contains("yesterday") || expr.contains("last night")) {
            return startOfDay(today.minusDays(1), zone);
        }
        if (expr.contains("tomorrow")) {
            return startOfDay(today.plusDays(1), zone);
        }

        Matcher daysAgo = DAYS_AGO.matcher(expr);
        if (daysAgo.find()) {
            return startOfDay(today.minusDays(Integer.parseInt(daysAgo.group(1))), zone);
        }

        DayOfWeek weekday = weekdayIn(expr);
        if (weekday != null) {
            // "last friday" means strictly before today; a bare "friday" may be today.
            LocalDate cursor = expr.contains("last") ? today.minusDays(1) : today;
            while (cursor.getDayOfWeek() != weekday) {
                cursor = cursor.minusDays(1);
            }
            return startOfDay(cursor, zone);
        }

        // Unrecognised phrasing: default to now rather than guessing a date (§6).
        return nowMillis;
    }

    private static DayOfWeek weekdayIn(String expr) {
        for (DayOfWeek day : DayOfWeek.values()) {
            if (expr.contains(day.name().toLowerCase(Locale.ROOT))) {
                return day;
            }
        }
        return null;
    }

    private static long startOfDay(LocalDate date, ZoneId zone) {
        return date.atStartOfDay(zone).toInstant().toEpochMilli();
    }

    private static ZoneId zoneOf(User user) {
        try {
            return user.getTimezone() == null || user.getTimezone().isBlank()
                    ? ZoneOffset.UTC
                    : ZoneId.of(user.getTimezone());
        } catch (DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }

    // --- category (F-1.9b) ---

    /**
     * Resolves a category label to one of the user's own categories, kind-aware so
     * an expense never lands in an income category.
     *
     * <p>The user's own words are tried <em>before</em> the model's label. The model
     * reliably picks a plausible category but not always the best one — it answered
     * "Other Income" for "got salary 3000" while the user had a "Salary" category,
     * and at full confidence, so no confidence check would have caught it. When a
     * category name appears verbatim in the message, that beats the model's guess.
     */
    String resolveCategory(String userId, TransactionType type, String guess, String message) {
        return resolveCategory(categoryRepository.findByUserId(userId), type, guess, message);
    }

    /** The same resolution against categories already loaded, so one draft is one query. */
    static String resolveCategory(List<Category> categories, TransactionType type, String guess, String message) {
        CategoryKind kind = kindFor(type);
        List<Category> candidates = categories.stream()
                .filter(c -> c.getKind() == kind)
                .toList();
        if (candidates.isEmpty()) {
            return null;
        }

        String text = normalize(message);
        // 1. The user named a category outright.
        Optional<Category> named = candidates.stream()
                .filter(c -> normalize(c.getName()).length() >= 3)
                .filter(c -> containsWord(text, normalize(c.getName())))
                .findFirst();
        if (named.isPresent()) {
            return named.get().getId();
        }

        String label = normalize(guess);
        if (!label.isEmpty()) {
            // 2. The model's label names a category exactly.
            Optional<Category> exact = candidates.stream()
                    .filter(c -> normalize(c.getName()).equals(label))
                    .findFirst();
            if (exact.isPresent()) {
                return exact.get().getId();
            }
            // 3. Partial overlap either way ("grocery" vs "Groceries").
            if (label.length() >= 3) {
                Optional<Category> partial = candidates.stream()
                        .filter(c -> {
                            String name = normalize(c.getName());
                            return name.contains(label) || label.contains(name);
                        })
                        .findFirst();
                if (partial.isPresent()) {
                    return partial.get().getId();
                }
            }
        }

        // 4. Synonyms, from the label first and then anything the user typed.
        for (String fragment : synonymFragments(label, text)) {
            Optional<Category> match = candidates.stream()
                    .filter(c -> normalize(c.getName()).contains(fragment))
                    .findFirst();
            if (match.isPresent()) {
                return match.get().getId();
            }
        }
        return null;
    }

    private static CategoryKind kindFor(TransactionType type) {
        return type == TransactionType.INCOME ? CategoryKind.INCOME : CategoryKind.EXPENSE;
    }

    private static List<String> synonymFragments(String label, String text) {
        List<String> fragments = new ArrayList<>(SYNONYMS.getOrDefault(label, List.of()));
        SYNONYMS.forEach((keyword, values) -> {
            if (containsWord(text, keyword)) {
                values.stream().filter(v -> !fragments.contains(v)).forEach(fragments::add);
            }
        });
        return fragments;
    }

    /** Substring match on whole words, so "tea" doesn't match inside "steak". */
    private static boolean containsWord(String haystack, String needle) {
        if (needle.isEmpty()) {
            return false;
        }
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return false;
            }
            boolean leftOk = at == 0 || !Character.isLetterOrDigit(haystack.charAt(at - 1));
            int end = at + needle.length();
            boolean rightOk = end == haystack.length() || !Character.isLetterOrDigit(haystack.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            from = at + 1;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    // --- account ---

    /** The account a chat capture lands in: the user's first active one by sort order. */
    private Account defaultAccount(String userId) {
        return accountRepository.findByUserId(userId).stream()
                .filter(a -> a.getStatus() == AccountStatus.ACTIVE)
                .min(Comparator.comparingInt(Account::getSortOrder)
                        .thenComparing(Account::getName, String.CASE_INSENSITIVE_ORDER))
                .orElse(null);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
