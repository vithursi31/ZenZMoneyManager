package com.zenzmoney.core.service.llm;

import com.zenzmoney.common.domain.AccountStatus;
import com.zenzmoney.common.domain.CategoryKind;
import com.zenzmoney.common.domain.IntentType;
import com.zenzmoney.common.domain.TransactionType;
import com.zenzmoney.core.entity.Account;
import com.zenzmoney.core.entity.Category;
import com.zenzmoney.core.entity.ParsedIntent;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.AccountRepository;
import com.zenzmoney.core.repository.CategoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * The resolver is where language becomes money and dates, so these tests are the
 * ones that matter most: an error here writes a wrong amount or a wrong day into
 * the ledger, and the user sees a plausible draft either way.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IntentResolverTest {

    private static final ZoneId COLOMBO = ZoneId.of("Asia/Colombo");   // UTC+5:30

    @Mock CategoryRepository categoryRepository;
    @Mock AccountRepository accountRepository;
    @InjectMocks IntentResolver resolver;

    // --- amount: minor units, never a float ---

    @Test
    void amount_convertsWholeAndDecimalToMinorUnits() {
        assertEquals(500L, IntentResolver.toMinorUnits("5", "USD"));
        assertEquals(1550L, IntentResolver.toMinorUnits("15.50", "USD"));
        assertEquals(450L, IntentResolver.toMinorUnits("4.5", "USD"));
        assertEquals(300000L, IntentResolver.toMinorUnits("3000", "USD"));
    }

    @Test
    void amount_isExactForValuesAFloatWouldRound() {
        // 0.1 and 0.29 are the classic binary-float casualties; integer parsing is exact.
        assertEquals(10L, IntentResolver.toMinorUnits("0.1", "USD"));
        assertEquals(29L, IntentResolver.toMinorUnits("0.29", "USD"));
        assertEquals(107L, IntentResolver.toMinorUnits("1.07", "USD"));
    }

    @Test
    void amount_roundsHalfUpOnExtraPrecision() {
        assertEquals(457L, IntentResolver.toMinorUnits("4.567", "USD"));
        assertEquals(456L, IntentResolver.toMinorUnits("4.564", "USD"));
        assertEquals(100L, IntentResolver.toMinorUnits("0.995", "USD"));
    }

    @Test
    void amount_honoursCurrenciesWithNoMinorUnit() {
        // JPY has 0 fraction digits: 1000 yen is 1000 minor units, not 100000.
        assertEquals(1000L, IntentResolver.toMinorUnits("1000", "JPY"));
        assertEquals(1001L, IntentResolver.toMinorUnits("1000.6", "JPY"));
    }

    @Test
    void amount_defaultsToTwoDigitsForAnUnknownCurrencyCode() {
        assertEquals(500L, IntentResolver.toMinorUnits("5", "ZZZ"));
        assertEquals(500L, IntentResolver.toMinorUnits("5", null));
    }

    @Test
    void amount_rejectsAnythingThatIsNotAPlainNumber() {
        assertNull(IntentResolver.toMinorUnits("$5", "USD"), "a currency symbol is the model ignoring the prompt");
        assertNull(IntentResolver.toMinorUnits("1,000", "USD"));
        assertNull(IntentResolver.toMinorUnits("five", "USD"));
        assertNull(IntentResolver.toMinorUnits("", "USD"));
        assertNull(IntentResolver.toMinorUnits(null, "USD"));
        assertNull(IntentResolver.toMinorUnits("-5", "USD"));
        assertNull(IntentResolver.toMinorUnits("5.5.5", "USD"));
    }

    // --- date: the phrase resolves in the user's zone, not the server's ---

    @Test
    void date_todayAndBlankResolveToTheCurrentMoment() {
        long now = instant("2026-07-28T06:00:00Z");
        assertEquals(now, IntentResolver.resolveDate("today", COLOMBO, now));
        assertEquals(now, IntentResolver.resolveDate(null, COLOMBO, now));
        assertEquals(now, IntentResolver.resolveDate("  ", COLOMBO, now));
        assertEquals(now, IntentResolver.resolveDate("this morning", COLOMBO, now));
    }

    @Test
    void date_yesterdayIsTheUsersLocalDayNotTheUtcDay() {
        // 00:30 UTC on the 28th is already 06:00 on the 28th in Colombo, so the
        // user's "yesterday" is the 27th local — a UTC-based answer would be wrong.
        long now = instant("2026-07-28T00:30:00Z");

        long colombo = IntentResolver.resolveDate("yesterday", COLOMBO, now);
        assertEquals(LocalDate.of(2026, 7, 27), localDate(colombo, COLOMBO));

        long utc = IntentResolver.resolveDate("yesterday", ZoneOffset.UTC, now);
        assertEquals(LocalDate.of(2026, 7, 27), localDate(utc, ZoneOffset.UTC));
        assertFalse(colombo == utc, "the same phrase resolves to different instants per zone");
    }

    @Test
    void date_handlesRelativeDayPhrases() {
        long now = instant("2026-07-28T06:00:00Z");   // Tuesday
        assertEquals(LocalDate.of(2026, 7, 29), localDate(IntentResolver.resolveDate("tomorrow", COLOMBO, now), COLOMBO));
        assertEquals(LocalDate.of(2026, 7, 26), localDate(IntentResolver.resolveDate("2 days ago", COLOMBO, now), COLOMBO));
        assertEquals(LocalDate.of(2026, 7, 26), localDate(IntentResolver.resolveDate("day before yesterday", COLOMBO, now), COLOMBO));
    }

    @Test
    void date_lastWeekdayLooksStrictlyBackwards() {
        long now = instant("2026-07-28T06:00:00Z");   // Tuesday 28 July 2026
        assertEquals(LocalDate.of(2026, 7, 24),
                localDate(IntentResolver.resolveDate("last Friday", COLOMBO, now), COLOMBO));
        assertEquals(LocalDate.of(2026, 7, 27),
                localDate(IntentResolver.resolveDate("monday", COLOMBO, now), COLOMBO));
    }

    @Test
    void date_fallsBackToNowForPhrasingItCannotRead() {
        long now = instant("2026-07-28T06:00:00Z");
        assertEquals(now, IntentResolver.resolveDate("the day after the eclipse", COLOMBO, now));
    }

    // --- category (F-1.9b) ---

    @Test
    void category_prefersACategoryTheUserNamedOverTheModelsGuess() {
        // The regression this exists for: qwen2.5:1.5b answered "Other Income" for
        // "got salary 3000" at confidence 1.0, with a Salary category available.
        when(categoryRepository.findByUserId("u1")).thenReturn(List.of(
                category("c-salary", "Salary", CategoryKind.INCOME),
                category("c-other", "Other Income", CategoryKind.INCOME)));

        String resolved = resolver.resolveCategory("u1", TransactionType.INCOME,
                "Other Income", "got salary 3000");

        assertEquals("c-salary", resolved);
    }

    @Test
    void category_takesTheModelsGuessWhenTheMessageNamesNothing() {
        when(categoryRepository.findByUserId("u1")).thenReturn(List.of(
                category("c-food", "Food & Drinks", CategoryKind.EXPENSE),
                category("c-groc", "Groceries", CategoryKind.EXPENSE)));

        assertEquals("c-food", resolver.resolveCategory("u1", TransactionType.EXPENSE,
                "Food & Drinks", "I have spent 5 for burger"));
    }

    @Test
    void category_matchesASingularGuessAgainstAPluralCategory() {
        when(categoryRepository.findByUserId("u1")).thenReturn(List.of(
                category("c-groc", "Groceries", CategoryKind.EXPENSE)));

        assertEquals("c-groc", resolver.resolveCategory("u1", TransactionType.EXPENSE,
                "grocery", "spent 15 at Keells for grocery"));
    }

    @Test
    void category_fallsBackToSynonyms() {
        when(categoryRepository.findByUserId("u1")).thenReturn(List.of(
                category("c-food", "Food & Drinks", CategoryKind.EXPENSE),
                category("c-transport", "Transport", CategoryKind.EXPENSE)));

        assertEquals("c-food", resolver.resolveCategory("u1", TransactionType.EXPENSE,
                "burger", "spent 5 on a burger"));
        assertEquals("c-transport", resolver.resolveCategory("u1", TransactionType.EXPENSE,
                "uber", "paid 12 for uber"));
    }

    @Test
    void category_neverCrossesKinds() {
        when(categoryRepository.findByUserId("u1")).thenReturn(List.of(
                category("c-salary", "Salary", CategoryKind.INCOME)));

        // "salary" is named outright, but this is an expense — an income category
        // would be rejected by TransactionService anyway, so don't offer it.
        assertNull(resolver.resolveCategory("u1", TransactionType.EXPENSE, "Salary", "paid salary 3000"));
    }

    @Test
    void category_returnsNullWhenNothingFits() {
        when(categoryRepository.findByUserId("u1")).thenReturn(List.of(
                category("c-food", "Food & Drinks", CategoryKind.EXPENSE)));

        assertNull(resolver.resolveCategory("u1", TransactionType.EXPENSE,
                "Veterinary", "spent 40 at the vet"));
    }

    // --- direction: the user's own verb beats the model's txnType ---

    /**
     * The regression this exists for: qwen2.5:1.5b answered EXPENSE and INCOME for
     * this same message on consecutive runs, and an INCOME reading turns a taxi fare
     * into money received.
     */
    @Test
    void type_prefersTheUsersVerbOverAModelThatFlippedTheDirection() {
        assertEquals(TransactionType.EXPENSE,
                IntentResolver.resolveType(TransactionType.INCOME, "paid 250 for uber yesterday"));
        assertEquals(TransactionType.INCOME,
                IntentResolver.resolveType(TransactionType.EXPENSE, "received 3000 salary"));
    }

    @Test
    void type_fillsADirectionTheModelLeftNull() {
        assertEquals(TransactionType.EXPENSE, IntentResolver.resolveType(null, "bought medicine for 800"));
        assertEquals(TransactionType.INCOME, IntentResolver.resolveType(null, "earned 500 freelancing"));
    }

    @Test
    void type_keepsTheModelsAnswerWhenTheMessageNamesNoDirection() {
        assertEquals(TransactionType.EXPENSE, IntentResolver.resolveType(TransactionType.EXPENSE, "uber 250"));
        assertNull(IntentResolver.resolveType(null, "uber 250"));
    }

    /** Both directions named is genuine ambiguity — an override would be a coin toss. */
    @Test
    void type_keepsTheModelsAnswerWhenTheMessageNamesBothDirections() {
        assertEquals(TransactionType.INCOME,
                IntentResolver.resolveType(TransactionType.INCOME, "paid my salary into savings"));
    }

    @Test
    void type_neverOverridesATransfer() {
        assertEquals(TransactionType.TRANSFER,
                IntentResolver.resolveType(TransactionType.TRANSFER, "paid 500 into my savings"));
    }

    @Test
    void type_matchesWholeWordsOnly() {
        assertNull(IntentResolver.resolveType(null, "paycheck arrived"), "'pay' must not match inside 'paycheck'");
    }

    // --- the whole draft ---

    @Test
    void resolve_buildsACompleteDraftFromAGoodExtraction() {
        User user = user("u1", "USD", "Asia/Colombo");
        when(accountRepository.findByUserId("u1")).thenReturn(List.of(account("a1", "Cash", "USD", 0)));
        when(categoryRepository.findByUserId("u1")).thenReturn(List.of(
                category("c-groc", "Groceries", CategoryKind.EXPENSE)));

        ParsedIntent draft = resolver.resolve(user,
                "I spent $15.50 in the Keells supermarket for grocery (tea things)",
                extraction(IntentType.CREATE_TRANSACTION, TransactionType.EXPENSE,
                        "15.50", "Groceries", "today", "Keells", "tea things", 0.93));

        assertTrue(draft.isComplete(), () -> "unexpected missing: " + draft.getMissingFields());
        assertEquals(TransactionType.EXPENSE, draft.getTxnType());
        assertEquals(1550L, draft.getAmountMinor());
        assertEquals("USD", draft.getCurrency());
        assertEquals("a1", draft.getAccountId());
        assertEquals("c-groc", draft.getCategoryId());
        assertEquals("Keells", draft.getPayeeName(), "the payee stays a name until confirm (§5.7)");
        assertEquals("tea things", draft.getNote());
        assertEquals(0.93, draft.getConfidence());
    }

    @Test
    void resolve_takesCurrencyFromTheUserNotTheMessage() {
        User user = user("u1", "LKR", "Asia/Colombo");
        when(accountRepository.findByUserId("u1")).thenReturn(List.of(account("a1", "Cash", "LKR", 0)));
        when(categoryRepository.findByUserId("u1")).thenReturn(List.of(
                category("c-food", "Food & Drinks", CategoryKind.EXPENSE)));

        ParsedIntent draft = resolver.resolve(user, "spent $5 on lunch",
                extraction(IntentType.CREATE_TRANSACTION, TransactionType.EXPENSE,
                        "5", "Food & Drinks", "today", null, "lunch", 0.9));

        assertEquals("LKR", draft.getCurrency(), "the '$' in the message must not set the currency (§3.3)");
        assertEquals(500L, draft.getAmountMinor());
    }

    @Test
    void resolve_picksTheFirstActiveAccountAndSkipsArchivedOnes() {
        User user = user("u1", "USD", "UTC");
        Account archived = account("a-arch", "Old Card", "USD", 0);
        archived.setStatus(AccountStatus.ARCHIVED);
        when(accountRepository.findByUserId("u1")).thenReturn(List.of(archived, account("a2", "Cash", "USD", 1)));
        when(categoryRepository.findByUserId("u1")).thenReturn(List.of(
                category("c-food", "Food & Drinks", CategoryKind.EXPENSE)));

        ParsedIntent draft = resolver.resolve(user, "spent 5 on lunch",
                extraction(IntentType.CREATE_TRANSACTION, TransactionType.EXPENSE,
                        "5", "Food & Drinks", "today", null, null, 0.9));

        assertEquals("a2", draft.getAccountId());
    }

    @Test
    void resolve_reportsMissingAccountWhenTheUserHasNone() {
        User user = user("u1", "USD", "UTC");
        when(accountRepository.findByUserId("u1")).thenReturn(List.of());
        when(categoryRepository.findByUserId("u1")).thenReturn(List.of());

        ParsedIntent draft = resolver.resolve(user, "spent 5 on lunch",
                extraction(IntentType.CREATE_TRANSACTION, TransactionType.EXPENSE,
                        "5", "Food & Drinks", "today", null, null, 0.9));

        assertFalse(draft.isComplete());
        assertTrue(draft.getMissingFields().contains("account"));
    }

    @Test
    void resolve_reportsMissingAmountWhenTheModelWroteJunk() {
        User user = user("u1", "USD", "UTC");
        when(accountRepository.findByUserId("u1")).thenReturn(List.of(account("a1", "Cash", "USD", 0)));
        when(categoryRepository.findByUserId("u1")).thenReturn(List.of(
                category("c-food", "Food & Drinks", CategoryKind.EXPENSE)));

        ParsedIntent draft = resolver.resolve(user, "spent some money on lunch",
                extraction(IntentType.CREATE_TRANSACTION, TransactionType.EXPENSE,
                        "a few dollars", "Food & Drinks", "today", null, null, 0.9));

        assertFalse(draft.isComplete());
        assertTrue(draft.getMissingFields().contains("amount"));
        assertNull(draft.getAmountMinor());
    }

    @Test
    void resolve_refusesTransfersFromChat() {
        User user = user("u1", "USD", "UTC");
        when(accountRepository.findByUserId("u1")).thenReturn(List.of(account("a1", "Cash", "USD", 0)));

        ParsedIntent draft = resolver.resolve(user, "moved 100 from cash to savings",
                extraction(IntentType.CREATE_TRANSACTION, TransactionType.TRANSFER,
                        "100", null, "today", null, null, 0.9));

        assertFalse(draft.isComplete());
        assertTrue(draft.getMissingFields().contains("transfer"), "transfers are out of scope (§2)");
    }

    @Test
    void resolve_appliesTheUsersVerbToTheDraftNotJustTheModelsType() {
        User user = user("u1", "USD", "UTC");
        when(accountRepository.findByUserId("u1")).thenReturn(List.of(account("a1", "Cash", "USD", 0)));
        when(categoryRepository.findByUserId("u1")).thenReturn(List.of(
                category("c-transport", "Transport", CategoryKind.EXPENSE)));

        ParsedIntent draft = resolver.resolve(user, "paid 250 for uber yesterday",
                extraction(IntentType.CREATE_TRANSACTION, TransactionType.INCOME,
                        "250", "Transport", "yesterday", "Uber", null, 1.0));

        assertEquals(TransactionType.EXPENSE, draft.getTxnType());
        assertEquals("c-transport", draft.getCategoryId(), "the kind filter now matches, so the guess survives");
        assertTrue(draft.isComplete(), () -> "unexpected missing: " + draft.getMissingFields());
    }

    /**
     * With no verb to settle it, a model that pairs INCOME with an expense category is
     * self-contradictory. Asking about the direction is the useful question; asking
     * "which category?" after silently dropping a good guess is not.
     */
    @Test
    void resolve_asksAboutDirectionWhenTheGuessKindContradictsTheType() {
        User user = user("u1", "USD", "UTC");
        when(accountRepository.findByUserId("u1")).thenReturn(List.of(account("a1", "Cash", "USD", 0)));
        when(categoryRepository.findByUserId("u1")).thenReturn(List.of(
                category("c-food", "Food & Drinks", CategoryKind.EXPENSE),
                category("c-salary", "Salary", CategoryKind.INCOME)));

        ParsedIntent draft = resolver.resolve(user, "coffee 500",
                extraction(IntentType.CREATE_TRANSACTION, TransactionType.INCOME,
                        "500", "Food & Drinks", "today", null, "coffee", 1.0));

        assertFalse(draft.isComplete());
        assertTrue(draft.getMissingFields().contains("type"));
        assertFalse(draft.getMissingFields().contains("category"), "the direction is what's in doubt");
        assertNull(draft.getTxnType(), "an unconfirmable direction must not sit in the draft");
    }

    @Test
    void resolve_leavesAnUnmatchedGuessAloneRatherThanCallingItAContradiction() {
        User user = user("u1", "USD", "UTC");
        when(accountRepository.findByUserId("u1")).thenReturn(List.of(account("a1", "Cash", "USD", 0)));
        when(categoryRepository.findByUserId("u1")).thenReturn(List.of(
                category("c-salary", "Salary", CategoryKind.INCOME)));

        ParsedIntent draft = resolver.resolve(user, "got 3000 consulting fee",
                extraction(IntentType.CREATE_TRANSACTION, TransactionType.INCOME,
                        "3000", "Consulting", "today", null, null, 0.9));

        assertEquals(TransactionType.INCOME, draft.getTxnType());
        assertTrue(draft.getMissingFields().contains("category"), "an unknown label is unusable, not contradictory");
        assertFalse(draft.getMissingFields().contains("type"));
    }

    @Test
    void resolve_marksANonCaptureIntentUnconfirmable() {
        User user = user("u1", "USD", "UTC");

        ParsedIntent draft = resolver.resolve(user, "how much did I spend on food?",
                extraction(IntentType.QUERY, null, null, null, null, null, null, 0.9));

        assertFalse(draft.isComplete(), "a question must never be confirmable into the ledger");
        assertTrue(draft.getMissingFields().contains("intent"));
    }

    // --- fixtures ---

    private static long instant(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    private static LocalDate localDate(long millis, ZoneId zone) {
        return Instant.ofEpochMilli(millis).atZone(zone).toLocalDate();
    }

    private static User user(String id, String currency, String timezone) {
        User u = new User();
        u.setId(id);
        u.setActiveCurrency(currency);
        u.setTimezone(timezone);
        return u;
    }

    private static Account account(String id, String name, String currency, int sortOrder) {
        Account a = new Account();
        a.setId(id);
        a.setName(name);
        a.setCurrency(currency);
        a.setSortOrder(sortOrder);
        a.setStatus(AccountStatus.ACTIVE);
        return a;
    }

    private static Category category(String id, String name, CategoryKind kind) {
        Category c = new Category();
        c.setId(id);
        c.setName(name);
        c.setKind(kind);
        return c;
    }

    private static LlmExtraction extraction(IntentType intent, TransactionType type, String amount,
                                            String categoryGuess, String dateExpr, String payee,
                                            String note, double confidence) {
        LlmExtraction e = new LlmExtraction();
        e.setIntent(intent);
        e.setTxnType(type);
        e.setAmountRaw(amount);
        e.setCategoryGuess(categoryGuess);
        e.setDateExpr(dateExpr);
        e.setPayee(payee);
        e.setNote(note);
        e.setConfidence(confidence);
        return e;
    }
}
