package com.zenzmoney.core.service.llm;

import com.zenzmoney.common.domain.CategoryKind;
import com.zenzmoney.common.domain.CategoryStatus;
import com.zenzmoney.common.domain.IntentType;
import com.zenzmoney.common.domain.TimeUtils;
import com.zenzmoney.common.domain.TransactionType;
import com.zenzmoney.core.entity.Category;
import com.zenzmoney.core.entity.ParsedIntent;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.CategoryRepository;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
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

    /** A whole message that is only an amount, with or without a symbol: "20", "$20", "rs 1500.50". */
    private static final Pattern BARE_AMOUNT =
            Pattern.compile("^[^\\d]{0,4}?(\\d{1,15}(?:\\.\\d{1,6})?)[^\\d]{0,4}?$");

    /** Any run of digits — the cheapest signal that a message the model gave up on is about money. */
    private static final Pattern DIGITS = Pattern.compile("\\d");

    /** Currencies with no minor unit still need a divisor; default to 2 decimals. */
    private static final int DEFAULT_FRACTION_DIGITS = 2;

    /**
     * Keyword to category-name fragments (F-1.14). Deliberately small: it covers
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
     * <p>Listed per language the app supports (F-1.26), because the guard is only
     * worth having in the language the user actually typed: qwen2.5 flipped
     * "pagué 250 por uber ayer" to INCOME exactly as it flipped the English one, and
     * an English-only list leaves those users unprotected. Accented and unaccented
     * spellings are both listed — {@link #normalize} lowercases but does not strip
     * accents, and users type both.
     */
    private static final List<String> EXPENSE_WORDS = List.of(
            // en
            "spent", "spend", "paid", "pay", "bought", "buy", "purchased", "purchase",
            "cost", "withdrew", "withdraw", "donated", "expense", "expenses",
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

    public IntentResolver(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    /**
     * Builds the draft from a standalone message — the first turn of a capture.
     * Never throws: anything that can't be resolved is recorded in
     * {@link ParsedIntent#getMissingFields()} so the flow can ask about it instead
     * of failing.
     *
     * @param message the user's original text, used as a second signal for category
     *                matching when the model's label is a worse fit than a word the
     *                user actually typed.
     */
    public ParsedIntent resolve(User user, String message, LlmExtraction extraction) {
        return resolve(user, message, extraction, null);
    }

    /**
     * Builds the draft, folding in whatever an earlier turn of the same conversation
     * already established.
     *
     * <p><b>Slot filling is what makes chat a conversation.</b> "I spent $20" leaves
     * the category open; the user's next word — "Food" — is not a new transaction but
     * the answer to a question, and a resolver that read it standalone would throw the
     * $20 away. So each slot is filled from the freshest source that has one: what the
     * model just read, then the message itself read as a bare answer, then
     * {@code pending}.
     *
     * <p>The message-as-bare-answer step exists because the extraction model is small
     * and a one-word reply carries almost no signal for it. "20" is unambiguous to a
     * regex and genuinely ambiguous to a 1.5B model, so the deterministic reading wins
     * where it applies.
     *
     * @param pending the live draft this conversation is refining, or null for a fresh
     *                capture.
     */
    public ParsedIntent resolve(User user, String message, LlmExtraction extraction, ParsedIntent pending) {
        ParsedIntent carried = continuationOf(pending);
        ParsedIntent draft = new ParsedIntent();
        draft.setIntent(resolveIntent(extraction, message, carried));
        draft.setCategoryGuess(firstNonNull(trimToNull(extraction.getCategoryGuess()),
                carried == null ? null : carried.getCategoryGuess()));
        draft.setNote(firstNonNull(trimToNull(extraction.getNote()),
                carried == null ? null : carried.getNote()));
        draft.setPayeeName(firstNonNull(trimToNull(extraction.getPayee()),
                carried == null ? null : carried.getPayeeName()));
        // An answered question only ever narrows the uncertainty the earlier turn had,
        // so a low-confidence one-word reply must not drag a good draft below the
        // threshold and strand the user in a clarification loop.
        draft.setConfidence(carried == null
                ? extraction.getConfidence()
                : Math.max(extraction.getConfidence(), carried.getConfidence()));

        if (draft.getIntent() != IntentType.CREATE_TRANSACTION) {
            // Not a capture. revalidate marks it incomplete so nothing can confirm it.
            revalidate(draft);
            return draft;
        }

        // The account is provisioned on confirm (§1.4), so the draft records only the
        // currency. Nothing here can name someone else's account.
        String currency = trimToNull(user.getActiveCurrency());
        draft.setCurrency(currency);
        draft.setAmountMinor(resolveAmount(extraction.getAmountRaw(), message, currency, carried));
        draft.setTxnDate(resolveTxnDate(extraction.getDateExpr(), user, carried));

        List<Category> categories = categoryRepository
                .findByUserIdAndStatus(user.getId(), CategoryStatus.ACTIVE);
        TransactionType type = firstNonNull(resolveType(extraction.getTxnType(), message),
                carried == null ? null : carried.getTxnType());

        // A guess whose kind contradicts the direction means one of the two is wrong,
        // and the draft cannot say which. Ask about the direction rather than silently
        // discarding a plausible category and asking the less useful "which category?".
        if (type != null && contradictsGuessKind(categories, type, draft.getCategoryGuess())) {
            type = null;
        }
        draft.setTxnType(type);

        if (type != null) {
            Category category = firstNonNull(
                    matchCategory(categories, type, draft.getCategoryGuess(), message),
                    carriedCategory(categories, type, carried));
            if (category != null) {
                draft.setCategoryId(category.getId());
                draft.setCategoryName(category.getName());
            }
        }
        revalidate(draft);
        return draft;
    }

    /**
     * Recomputes {@link ParsedIntent#getMissingFields()} from what the draft currently
     * holds. The single definition of "incomplete", so a draft the user edited in the
     * preview is judged by exactly the rule that judged the model's first reading.
     */
    public void revalidate(ParsedIntent draft) {
        List<String> missing = draft.getMissingFields();
        missing.clear();
        if (draft.getIntent() != IntentType.CREATE_TRANSACTION) {
            missing.add("intent");
            return;
        }
        if (trimToNull(draft.getCurrency()) == null) {
            missing.add("currency");
        }
        if (draft.getAmountMinor() == null || draft.getAmountMinor() <= 0) {
            missing.add("amount");
        }
        if (draft.getTxnType() == null) {
            // Asking which category before knowing the direction offers the wrong half
            // of the list, so the direction is the only thing worth asking about here.
            missing.add("type");
        } else if (trimToNull(draft.getCategoryId()) == null) {
            missing.add("category");
        }
    }

    // --- continuation ---

    /** The pending draft, but only when it is one this turn could actually be answering. */
    private static ParsedIntent continuationOf(ParsedIntent pending) {
        return pending != null && pending.getIntent() == IntentType.CREATE_TRANSACTION ? pending : null;
    }

    /**
     * What the user is asking for. A reply inside an open capture stays a capture even
     * when the model reads nothing usable in it — "20" on its own is UNKNOWN to the
     * model and obviously an answer to the question that preceded it. A model that
     * reads a <em>different</em> intent is believed: the user changed the subject.
     *
     * <p>With no capture open, a message the model gave up on is still treated as one
     * when it plainly talks about money, so the flow asks what is missing rather than
     * answering "I couldn't tell what you wanted to record".
     */
    private static IntentType resolveIntent(LlmExtraction extraction, String message, ParsedIntent carried) {
        IntentType read = extraction.getIntent();
        if (read != IntentType.UNKNOWN) {
            return read;
        }
        if (extraction.isFailed()) {
            // Nothing was read at all; the flow answers "try again" rather than guessing.
            return IntentType.UNKNOWN;
        }
        if (carried != null || looksFinancial(message)) {
            return IntentType.CREATE_TRANSACTION;
        }
        return IntentType.UNKNOWN;
    }

    /**
     * True when the message carries a number, a direction word, or a word the synonym
     * map knows — the three signals that make an unreadable message worth a question
     * instead of a shrug.
     */
    static boolean looksFinancial(String message) {
        String text = normalize(message);
        if (text.isEmpty()) {
            return false;
        }
        if (DIGITS.matcher(text).find()) {
            return true;
        }
        return EXPENSE_WORDS.stream().anyMatch(word -> containsWord(text, word))
                || INCOME_WORDS.stream().anyMatch(word -> containsWord(text, word))
                || SYNONYMS.keySet().stream().anyMatch(word -> containsWord(text, word));
    }

    // --- slot filling ---

    private static Long resolveAmount(String amountRaw, String message, String currency, ParsedIntent carried) {
        if (currency == null) {
            return null;
        }
        Long amount = firstNonNull(toMinorUnits(amountRaw, currency), bareAmount(message, currency));
        if (amount == null && carried != null) {
            amount = carried.getAmountMinor();
        }
        return amount != null && amount > 0 ? amount : null;
    }

    /**
     * Reads a message that is <em>nothing but</em> an amount — "20", "$20", "20.50" —
     * as the answer to "how much was that?". Anchored at both ends on purpose: a
     * number buried in a sentence is the model's to interpret, and grabbing it here
     * would turn "paid at pump 7" into a 7-rupee expense.
     */
    static Long bareAmount(String message, String currency) {
        if (message == null) {
            return null;
        }
        Matcher matcher = BARE_AMOUNT.matcher(message.trim());
        return matcher.matches() ? toMinorUnits(matcher.group(1), currency) : null;
    }

    /**
     * The date the draft should carry. A follow-up that says nothing about time keeps
     * the day the original message named — otherwise answering "which category?"
     * would silently move yesterday's expense to today.
     */
    private static Long resolveTxnDate(String dateExpr, User user, ParsedIntent carried) {
        if ((dateExpr == null || dateExpr.isBlank()) && carried != null && carried.getTxnDate() != null) {
            return carried.getTxnDate();
        }
        return resolveDate(dateExpr, zoneOf(user), TimeUtils.now());
    }

    /** The carried category, re-checked against the current direction before it is reused. */
    private static Category carriedCategory(List<Category> categories, TransactionType type, ParsedIntent carried) {
        if (carried == null || carried.getCategoryId() == null) {
            return null;
        }
        return categories.stream()
                .filter(c -> c.getId().equals(carried.getCategoryId()))
                .filter(c -> c.getKind() == kindFor(type))
                .findFirst()
                .orElse(null);
    }

    // --- direction ---

    /**
     * Decides income-vs-expense, preferring a direction word the user typed over the
     * model's {@code txnType}. Also fills the direction the model left null, which
     * turns a clarifying question the user can already see the answer to into no
     * question at all.
     *
     * <p><b>With no such word, an unsupported INCOME becomes a question rather than a
     * guess.</b> "rent 45000" does not say whether the user paid rent or collected
     * it, and neither does "uber 250" — a driver's fare and a passenger's are the
     * same three characters. The prompt eval measured the model at 4/16 on INCOME and
     * 8/8 on EXPENSE, so its EXPENSE is worth taking and its INCOME is not: a wrong
     * EXPENSE understates a month, a wrong INCOME flips its sign. Returning null puts
     * the two-option question in front of the user, which is one tap and always right.
     *
     * @return the direction, or null when nothing has established it.
     */
    static TransactionType resolveType(TransactionType modelType, String message) {
        String text = normalize(message);
        boolean out = EXPENSE_WORDS.stream().anyMatch(word -> containsWord(text, word));
        boolean in = INCOME_WORDS.stream().anyMatch(word -> containsWord(text, word));

        if (out != in) {
            return out ? TransactionType.EXPENSE : TransactionType.INCOME;
        }
        // Neither direction named, or both. All that is left is the model's answer,
        // and it is only worth trusting in one direction.
        return modelType == TransactionType.INCOME ? null : modelType;
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
    public static Long toMinorUnits(String rawAmount, String currencyCode) {
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

    // --- category (F-1.14) ---

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
        return resolveCategory(
                categoryRepository.findByUserIdAndStatus(userId, CategoryStatus.ACTIVE), type, guess, message);
    }

    /** The same resolution against categories already loaded, so one draft is one query. */
    static String resolveCategory(List<Category> categories, TransactionType type, String guess, String message) {
        Category match = matchCategory(categories, type, guess, message);
        return match == null ? null : match.getId();
    }

    /** The matched row rather than its id, so the draft can carry the name it will display. */
    static Category matchCategory(List<Category> categories, TransactionType type, String guess, String message) {
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
            return named.get();
        }

        String label = normalize(guess);
        if (!label.isEmpty()) {
            // 2. The model's label names a category exactly.
            Optional<Category> exact = candidates.stream()
                    .filter(c -> normalize(c.getName()).equals(label))
                    .findFirst();
            if (exact.isPresent()) {
                return exact.get();
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
                    return partial.get();
                }
            }
        }

        // 4. Synonyms, from the label first and then anything the user typed.
        for (String fragment : synonymFragments(label, text)) {
            Optional<Category> match = candidates.stream()
                    .filter(c -> normalize(c.getName()).contains(fragment))
                    .findFirst();
            if (match.isPresent()) {
                return match.get();
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

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** The freshest of two candidate slot values — this turn's reading, else the conversation's. */
    private static <T> T firstNonNull(T fresh, T carried) {
        return fresh != null ? fresh : carried;
    }
}
