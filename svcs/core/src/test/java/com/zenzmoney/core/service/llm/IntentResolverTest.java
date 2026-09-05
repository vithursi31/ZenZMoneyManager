package com.zenzmoney.core.service.llm;

import com.zenzmoney.common.domain.CategoryStatus;
import com.zenzmoney.common.domain.CategoryKind;
import com.zenzmoney.common.domain.IntentType;
import com.zenzmoney.common.domain.RecurringCadence;
import com.zenzmoney.common.domain.TransactionType;
import com.zenzmoney.core.entity.Category;
import com.zenzmoney.core.entity.ParsedIntent;
import com.zenzmoney.core.entity.User;
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

    // --- category (F-1.14) ---

    @Test
    void category_prefersACategoryTheUserNamedOverTheModelsGuess() {
        // The regression this exists for: qwen2.5:1.5b answered "Other Income" for
        // "got salary 3000" at confidence 1.0, with a Salary category available.
        when(categoryRepository.findByUserIdAndStatus("u1", CategoryStatus.ACTIVE)).thenReturn(List.of(
                category("c-salary", "Salary", CategoryKind.INCOME),
                category("c-other", "Other Income", CategoryKind.INCOME)));

        String resolved = resolver.resolveCategory("u1", TransactionType.INCOME,
                "Other Income", "got salary 3000");

        assertEquals("c-salary", resolved);
    }

    @Test
    void category_takesTheModelsGuessWhenTheMessageNamesNothing() {
        when(categoryRepository.findByUserIdAndStatus("u1", CategoryStatus.ACTIVE)).thenReturn(List.of(
                category("c-food", "Food & Drinks", CategoryKind.EXPENSE),
                category("c-groc", "Groceries", CategoryKind.EXPENSE)));

        assertEquals("c-food", resolver.resolveCategory("u1", TransactionType.EXPENSE,
                "Food & Drinks", "I have spent 5 for burger"));
    }

    @Test
    void category_matchesASingularGuessAgainstAPluralCategory() {
        when(categoryRepository.findByUserIdAndStatus("u1", CategoryStatus.ACTIVE)).thenReturn(List.of(
                category("c-groc", "Groceries", CategoryKind.EXPENSE)));

        assertEquals("c-groc", resolver.resolveCategory("u1", TransactionType.EXPENSE,
                "grocery", "spent 15 at Keells for grocery"));
    }

    @Test
    void category_fallsBackToSynonyms() {
        when(categoryRepository.findByUserIdAndStatus("u1", CategoryStatus.ACTIVE)).thenReturn(List.of(
                category("c-food", "Food & Drinks", CategoryKind.EXPENSE),
                category("c-transport", "Transport", CategoryKind.EXPENSE)));

        assertEquals("c-food", resolver.resolveCategory("u1", TransactionType.EXPENSE,
                "burger", "spent 5 on a burger"));
        assertEquals("c-transport", resolver.resolveCategory("u1", TransactionType.EXPENSE,
                "uber", "paid 12 for uber"));
    }

    @Test
    void category_neverCrossesKinds() {
        when(categoryRepository.findByUserIdAndStatus("u1", CategoryStatus.ACTIVE)).thenReturn(List.of(
                category("c-salary", "Salary", CategoryKind.INCOME)));

        // "salary" is named outright, but this is an expense — an income category
        // would be rejected by TransactionService anyway, so don't offer it.
        assertNull(resolver.resolveCategory("u1", TransactionType.EXPENSE, "Salary", "paid salary 3000"));
    }

    @Test
    void category_returnsNullWhenNothingFits() {
        when(categoryRepository.findByUserIdAndStatus("u1", CategoryStatus.ACTIVE)).thenReturn(List.of(
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
    void type_keepsTheModelsExpenseWhenTheMessageNamesNoDirection() {
        assertEquals(TransactionType.EXPENSE, IntentResolver.resolveType(TransactionType.EXPENSE, "uber 250"));
        assertNull(IntentResolver.resolveType(null, "uber 250"));
    }

    /**
     * The terse entries the prompt eval caught: a bare noun and a number, where the
     * model answered INCOME for every one of them. None of these messages says which
     * way the money went, and two of them genuinely could go either way — a landlord
     * collects the rent a tenant pays, and a driver earns the fare a passenger spends.
     * Asking costs one tap; guessing books somebody's rent as income.
     */
    @Test
    void type_asksRatherThanGuessingWhenNothingSupportsTheModelsIncome() {
        assertNull(IntentResolver.resolveType(TransactionType.INCOME, "rent 45000"));
        assertNull(IntentResolver.resolveType(TransactionType.INCOME, "uber 250"));
        assertNull(IntentResolver.resolveType(TransactionType.INCOME, "coffee 500"));
        assertNull(IntentResolver.resolveType(TransactionType.INCOME, "lunch 1500"));
        assertNull(IntentResolver.resolveType(TransactionType.INCOME, "café 500"));
    }

    @Test
    void type_takesTheModelsIncomeOnceTheUsersOwnWordsSupportIt() {
        assertEquals(TransactionType.INCOME, IntentResolver.resolveType(TransactionType.INCOME, "rent 45000 received"));
        assertEquals(TransactionType.INCOME, IntentResolver.resolveType(TransactionType.INCOME, "got salary 3000 today"));
        assertEquals(TransactionType.INCOME,
                IntentResolver.resolveType(TransactionType.INCOME, "received 450 freelance payment"));
    }

    /**
     * Both directions named is genuine ambiguity, and the model's coin toss is not a
     * tiebreaker — the same rule as naming neither.
     */
    @Test
    void type_asksWhenTheMessageNamesBothDirectionsAndTheModelSaysIncome() {
        assertNull(IntentResolver.resolveType(TransactionType.INCOME, "paid my salary into savings"));
    }

    @Test
    void type_matchesWholeWordsOnly() {
        assertNull(IntentResolver.resolveType(null, "paycheck arrived"), "'pay' must not match inside 'paycheck'");
    }

    // --- direction in French and Spanish ---

    /**
     * The guard has to work in the language the user typed. qwen2.5 flipped
     * "pagué 250 por uber ayer" to INCOME the same way it flipped the English
     * sentence, so an English-only word list would leave those users unprotected.
     */
    @Test
    void type_readsFrenchDirectionWords() {
        assertEquals(TransactionType.EXPENSE,
                IntentResolver.resolveType(TransactionType.INCOME, "j'ai payé 250 pour uber hier"));
        assertEquals(TransactionType.EXPENSE,
                IntentResolver.resolveType(null, "j'ai dépensé 15 pour le déjeuner"));
        assertEquals(TransactionType.INCOME,
                IntentResolver.resolveType(TransactionType.EXPENSE, "j'ai reçu mon salaire de 3000"));
    }

    @Test
    void type_readsSpanishDirectionWords() {
        assertEquals(TransactionType.EXPENSE,
                IntentResolver.resolveType(TransactionType.INCOME, "pagué 250 por uber ayer"));
        assertEquals(TransactionType.EXPENSE,
                IntentResolver.resolveType(null, "compré medicina por 800 en la farmacia"));
        assertEquals(TransactionType.INCOME,
                IntentResolver.resolveType(TransactionType.EXPENSE, "recibí mi sueldo de 3000"));
    }

    /** Users type accents inconsistently, and normalization does not strip them. */
    @Test
    void type_readsDirectionWordsWithAndWithoutAccents() {
        assertEquals(TransactionType.EXPENSE, IntentResolver.resolveType(null, "gaste 500 en el almuerzo"));
        assertEquals(TransactionType.EXPENSE, IntentResolver.resolveType(null, "j'ai depense 500"));
        assertEquals(TransactionType.INCOME, IntentResolver.resolveType(null, "recibi 3000"));
    }

    /** Same ambiguity rule across languages: two directions named means don't guess. */
    @Test
    void type_asksWhenAFrenchMessageNamesBothDirectionsAndTheModelSaysIncome() {
        assertNull(IntentResolver.resolveType(TransactionType.INCOME, "j'ai payé avec mon salaire"));
    }

    // --- non-English messages through the whole draft ---

    /**
     * The prompt makes the model normalize its output to English — a category name
     * copied from the closed list and a date <em>phrase</em> in English ("hier" comes
     * back as "yesterday"). That contract is what lets the resolver's English-only
     * date and synonym tables serve a French message; this pins it, because a prompt
     * change that let the model answer in the user's language would break resolution
     * silently.
     */
    @Test
    void resolve_buildsACompleteDraftFromAFrenchMessage() {
        User user = user("u1", "EUR", "UTC");
        when(categoryRepository.findByUserIdAndStatus("u1", CategoryStatus.ACTIVE)).thenReturn(List.of(
                category("c-transport", "Transport", CategoryKind.EXPENSE)));

        ParsedIntent draft = resolver.resolve(user, "j'ai payé 250 pour uber hier",
                extraction(IntentType.CREATE_TRANSACTION, TransactionType.INCOME,
                        "250", "Transport", "yesterday", "Uber", null, 1.0));

        assertTrue(draft.isComplete(), () -> "unexpected missing: " + draft.getMissingFields());
        assertEquals(TransactionType.EXPENSE, draft.getTxnType(), "'payé' outranks the model's INCOME");
        assertEquals(25000L, draft.getAmountMinor());
        assertEquals("c-transport", draft.getCategoryId());
    }

    @Test
    void resolve_buildsACompleteDraftFromASpanishMessage() {
        User user = user("u1", "EUR", "UTC");
        when(categoryRepository.findByUserIdAndStatus("u1", CategoryStatus.ACTIVE)).thenReturn(List.of(
                category("c-health", "Health", CategoryKind.EXPENSE)));

        ParsedIntent draft = resolver.resolve(user, "compré medicina por 800 en la farmacia",
                extraction(IntentType.CREATE_TRANSACTION, null,
                        "800", "Health", "today", "Farmacia", null, 0.9));

        assertTrue(draft.isComplete(), () -> "unexpected missing: " + draft.getMissingFields());
        assertEquals(TransactionType.EXPENSE, draft.getTxnType(), "'compré' fills the null the model left");
        assertEquals(80000L, draft.getAmountMinor());
        assertEquals("Farmacia", draft.getPayeeName());
    }

    /**
     * Known limitation, pinned deliberately: the "a word the user typed beats the
     * model's label" shortcut only fires when the message contains the category name,
     * which an unlocalized category list never will in French. Resolution falls back
     * to the model's guess — fine here, but it means a bad guess has no second
     * opinion for non-English messages.
     */
    @Test
    void category_cannotUseTheUsersOwnWordsWhenTheyAreNotInTheCategoryLanguage() {
        when(categoryRepository.findByUserIdAndStatus("u1", CategoryStatus.ACTIVE)).thenReturn(List.of(
                category("c-food", "Food & Drinks", CategoryKind.EXPENSE),
                category("c-groc", "Groceries", CategoryKind.EXPENSE)));

        // "déjeuner" matches no category name and no synonym, so only the guess decides.
        assertEquals("c-groc", resolver.resolveCategory("u1", TransactionType.EXPENSE,
                "Groceries", "j'ai dépensé 15 pour le déjeuner"));
        assertNull(resolver.resolveCategory("u1", TransactionType.EXPENSE,
                null, "j'ai dépensé 15 pour le déjeuner"));
    }

    // --- number styles the user's locale produces ---

    /**
     * A European decimal comma is not a number this contract accepts, and that is the
     * safe outcome: the amount goes missing and the user is asked, rather than 15,50
     * being read as 1550 or 15. (The model normally converts it to "15.50" itself —
     * this covers the run where it echoes the user's form instead.)
     *
     * <p>A <em>grouped</em> comma is a different case and is now accepted — see
     * {@link #readsAThousandsSeparatedAmount}. What stays refused is everything the
     * three-digit grouping cannot vouch for.
     */
    @Test
    void amount_rejectsADecimalCommaRatherThanGuessingWhatItMeant() {
        assertNull(IntentResolver.toMinorUnits("15,50", "EUR"));
        assertNull(IntentResolver.toMinorUnits("1 500", "EUR"), "a space thousands separator is not a number");
    }

    /**
     * The dangerous one, and the reason a digits-only amount is not enough on its own:
     * "1.500" is 1500 in es-ES and 1.5 in en-US, and nothing downstream can tell. The
     * model returned "1.5" for "gasté 1.500 en el supermercado" — a 1000x error that
     * parses perfectly. Documented here because the fix belongs in extraction (prefer
     * a number the user actually typed, and ask when it is ambiguous), not in this
     * conversion.
     */
    @Test
    void amount_cannotTellAThousandsDotFromADecimalDot() {
        assertEquals(150L, IntentResolver.toMinorUnits("1.5", "EUR"));
        assertEquals(150000L, IntentResolver.toMinorUnits("1500", "EUR"));
    }

    // --- the whole draft ---

    @Test
    void resolve_buildsACompleteDraftFromAGoodExtraction() {
        User user = user("u1", "USD", "Asia/Colombo");
        when(categoryRepository.findByUserIdAndStatus("u1", CategoryStatus.ACTIVE)).thenReturn(List.of(
                category("c-groc", "Groceries", CategoryKind.EXPENSE)));

        ParsedIntent draft = resolver.resolve(user,
                "I spent $15.50 in the Keells supermarket for grocery (tea things)",
                extraction(IntentType.CREATE_TRANSACTION, TransactionType.EXPENSE,
                        "15.50", "Groceries", "today", "Keells", "tea things", 0.93));

        assertTrue(draft.isComplete(), () -> "unexpected missing: " + draft.getMissingFields());
        assertEquals(TransactionType.EXPENSE, draft.getTxnType());
        assertEquals(1550L, draft.getAmountMinor());
        assertEquals("USD", draft.getCurrency());
        assertEquals("c-groc", draft.getCategoryId());
        assertEquals("Keells", draft.getPayeeName(), "the payee stays a name until confirm (§5.7)");
        assertEquals("tea things", draft.getNote());
        assertEquals(0.93, draft.getConfidence());
    }

    @Test
    void resolve_takesCurrencyFromTheUserNotTheMessage() {
        User user = user("u1", "LKR", "Asia/Colombo");
        when(categoryRepository.findByUserIdAndStatus("u1", CategoryStatus.ACTIVE)).thenReturn(List.of(
                category("c-food", "Food & Drinks", CategoryKind.EXPENSE)));

        ParsedIntent draft = resolver.resolve(user, "spent $5 on lunch",
                extraction(IntentType.CREATE_TRANSACTION, TransactionType.EXPENSE,
                        "5", "Food & Drinks", "today", null, "lunch", 0.9));

        assertEquals("LKR", draft.getCurrency(), "the '$' in the message must not set the currency (§3.3)");
        assertEquals(500L, draft.getAmountMinor());
    }

    /**
     * A draft never names an account — the user has one and it is resolved on confirm
     * (§1.4). It does need the currency, because the amount is converted to minor units
     * against it, so a user who has not onboarded cannot produce a complete draft.
     */
    @Test
    void resolve_reportsMissingCurrencyBeforeOnboarding() {
        User user = user("u1", null, "UTC");
        when(categoryRepository.findByUserIdAndStatus("u1", CategoryStatus.ACTIVE)).thenReturn(List.of(
                category("c-food", "Food & Drinks", CategoryKind.EXPENSE)));

        ParsedIntent draft = resolver.resolve(user, "spent 5 on lunch",
                extraction(IntentType.CREATE_TRANSACTION, TransactionType.EXPENSE,
                        "5", "Food & Drinks", "today", null, null, 0.9));

        assertFalse(draft.isComplete());
        assertTrue(draft.getMissingFields().contains("currency"));
    }

    @Test
    void resolve_reportsMissingAmountWhenTheModelWroteJunk() {
        User user = user("u1", "USD", "UTC");
        when(categoryRepository.findByUserIdAndStatus("u1", CategoryStatus.ACTIVE)).thenReturn(List.of(
                category("c-food", "Food & Drinks", CategoryKind.EXPENSE)));

        ParsedIntent draft = resolver.resolve(user, "spent some money on lunch",
                extraction(IntentType.CREATE_TRANSACTION, TransactionType.EXPENSE,
                        "a few dollars", "Food & Drinks", "today", null, null, 0.9));

        assertFalse(draft.isComplete());
        assertTrue(draft.getMissingFields().contains("amount"));
        assertNull(draft.getAmountMinor());
    }

    @Test
    void resolve_appliesTheUsersVerbToTheDraftNotJustTheModelsType() {
        User user = user("u1", "USD", "UTC");
        when(categoryRepository.findByUserIdAndStatus("u1", CategoryStatus.ACTIVE)).thenReturn(List.of(
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
        when(categoryRepository.findByUserIdAndStatus("u1", CategoryStatus.ACTIVE)).thenReturn(List.of(
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
        when(categoryRepository.findByUserIdAndStatus("u1", CategoryStatus.ACTIVE)).thenReturn(List.of(
                category("c-salary", "Salary", CategoryKind.INCOME)));

        ParsedIntent draft = resolver.resolve(user, "received 3000 consulting fee",
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

    // --- income, end to end ---

    @Test
    void resolve_buildsACompleteIncomeDraft() {
        User user = user("u1", "USD", "UTC");
        when(categoryRepository.findByUserIdAndStatus("u1", CategoryStatus.ACTIVE)).thenReturn(List.of(
                category("c-free", "Freelance", CategoryKind.INCOME),
                category("c-food", "Food & Drinks", CategoryKind.EXPENSE)));

        ParsedIntent draft = resolver.resolve(user, "I received $500 from freelancing",
                extraction(IntentType.CREATE_TRANSACTION, TransactionType.INCOME,
                        "500", "Freelance", "today", null, "freelancing", 0.93));

        assertTrue(draft.isComplete(), () -> "unexpected missing: " + draft.getMissingFields());
        assertEquals(TransactionType.INCOME, draft.getTxnType());
        assertEquals(50000L, draft.getAmountMinor());
        assertEquals("c-free", draft.getCategoryId());
        assertEquals("Freelance", draft.getCategoryName(), "the preview renders the name, not the id");
    }

    @Test
    void resolve_asksOnlyForTheAmountWhenTheIncomeCategoryIsAlreadyClear() {
        User user = user("u1", "USD", "UTC");
        when(categoryRepository.findByUserIdAndStatus("u1", CategoryStatus.ACTIVE)).thenReturn(List.of(
                category("c-salary", "Salary", CategoryKind.INCOME)));

        ParsedIntent draft = resolver.resolve(user, "my salary was deposited",
                extraction(IntentType.CREATE_TRANSACTION, TransactionType.INCOME,
                        null, "Salary", "today", null, "salary", 0.9));

        assertEquals(List.of("amount"), draft.getMissingFields());
        assertEquals(TransactionType.INCOME, draft.getTxnType());
        assertEquals("c-salary", draft.getCategoryId());
    }

    // --- a message that is plainly about money but unreadable to the model ---

    @Test
    void resolve_treatsAnUnreadMessageAsACaptureWhenItPlainlyTalksAboutMoney() {
        User user = user("u1", "USD", "UTC");
        when(categoryRepository.findByUserIdAndStatus("u1", CategoryStatus.ACTIVE)).thenReturn(List.of(
                category("c-food", "Food & Drinks", CategoryKind.EXPENSE)));

        ParsedIntent draft = resolver.resolve(user, "20",
                extraction(IntentType.UNKNOWN, null, null, null, null, null, null, 0.2));

        assertEquals(IntentType.CREATE_TRANSACTION, draft.getIntent(),
                "asking which direction beats answering \"I couldn't tell what you wanted\"");
        assertEquals(2000L, draft.getAmountMinor(), "a message that is only a number is an amount");
        assertTrue(draft.getMissingFields().contains("type"));
    }

    @Test
    void resolve_leavesAMessageWithNoMoneySignalAlone() {
        User user = user("u1", "USD", "UTC");

        ParsedIntent draft = resolver.resolve(user, "hello there",
                extraction(IntentType.UNKNOWN, null, null, null, null, null, null, 0.2));

        assertEquals(IntentType.UNKNOWN, draft.getIntent());
        assertTrue(draft.getMissingFields().contains("intent"));
    }

    @Test
    void resolve_doesNotGuessAtACaptureWhenTheModelWasNeverReached() {
        User user = user("u1", "USD", "UTC");

        ParsedIntent draft = resolver.resolve(user, "spent 20 on fuel", LlmExtraction.failed());

        assertEquals(IntentType.UNKNOWN, draft.getIntent(),
                "an outage is answered with \"try again\", not with a half-invented draft");
    }

    @Test
    void bareAmount_onlyReadsAMessageThatIsNothingButAnAmount() {
        assertEquals(2000L, IntentResolver.bareAmount("20", "USD"));
        assertEquals(2000L, IntentResolver.bareAmount("$20", "USD"));
        assertEquals(1750L, IntentResolver.bareAmount("17.50", "USD"));
        assertEquals(150000L, IntentResolver.bareAmount("rs 1500", "USD"));
        assertNull(IntentResolver.bareAmount("paid at pump 7", "USD"),
                "a number inside a sentence is the model's to read, not a bare answer");
        assertNull(IntentResolver.bareAmount("Food & Drinks", "USD"));
    }

    // --- slot filling across turns ---

    @Test
    void resolve_keepsTheAmountFromTheEarlierTurnWhenTheAnswerNamesTheCategory() {
        User user = user("u1", "USD", "UTC");
        when(categoryRepository.findByUserIdAndStatus("u1", CategoryStatus.ACTIVE)).thenReturn(List.of(
                category("c-food", "Food & Drinks", CategoryKind.EXPENSE)));

        // "I spent $20" left the category open; the user answers with one word.
        ParsedIntent draft = resolver.resolve(user, "Food",
                extraction(IntentType.UNKNOWN, null, null, null, null, null, null, 0.2),
                pending(TransactionType.EXPENSE, 2000L, null, 0.9));

        assertTrue(draft.isComplete(), () -> "unexpected missing: " + draft.getMissingFields());
        assertEquals(2000L, draft.getAmountMinor(), "the $20 must survive the follow-up");
        assertEquals("c-food", draft.getCategoryId());
        assertEquals(TransactionType.EXPENSE, draft.getTxnType());
    }

    @Test
    void resolve_keepsTheCategoryFromTheEarlierTurnWhenTheAnswerIsAnAmount() {
        User user = user("u1", "USD", "UTC");
        when(categoryRepository.findByUserIdAndStatus("u1", CategoryStatus.ACTIVE)).thenReturn(List.of(
                category("c-food", "Food & Drinks", CategoryKind.EXPENSE)));

        // "I paid for food" left the amount open.
        ParsedIntent draft = resolver.resolve(user, "20",
                extraction(IntentType.UNKNOWN, null, null, null, null, null, null, 0.2),
                pending(TransactionType.EXPENSE, null, "c-food", 0.9));

        assertTrue(draft.isComplete(), () -> "unexpected missing: " + draft.getMissingFields());
        assertEquals(2000L, draft.getAmountMinor());
        assertEquals("c-food", draft.getCategoryId());
    }

    @Test
    void resolve_doesNotLetAOneWordAnswerDragAGoodDraftBelowTheThreshold() {
        User user = user("u1", "USD", "UTC");
        when(categoryRepository.findByUserIdAndStatus("u1", CategoryStatus.ACTIVE)).thenReturn(List.of(
                category("c-food", "Food & Drinks", CategoryKind.EXPENSE)));

        ParsedIntent draft = resolver.resolve(user, "Food",
                extraction(IntentType.UNKNOWN, null, null, null, null, null, null, 0.1),
                pending(TransactionType.EXPENSE, 2000L, null, 0.9));

        assertEquals(0.9, draft.getConfidence(),
                "an answered question narrows the uncertainty; it cannot widen it");
    }

    @Test
    void resolve_keepsTheDayTheFirstMessageNamedWhenTheAnswerSaysNothingAboutTime() {
        User user = user("u1", "USD", "Asia/Colombo");
        when(categoryRepository.findByUserIdAndStatus("u1", CategoryStatus.ACTIVE)).thenReturn(List.of(
                category("c-fuel", "Fuel", CategoryKind.EXPENSE)));
        ParsedIntent pending = pending(TransactionType.EXPENSE, 3000L, null, 0.9);
        pending.setTxnDate(instant("2026-07-27T00:00:00Z"));

        ParsedIntent draft = resolver.resolve(user, "Fuel",
                extraction(IntentType.UNKNOWN, null, null, null, null, null, null, 0.2), pending);

        assertEquals(instant("2026-07-27T00:00:00Z"), draft.getTxnDate(),
                "answering \"which category?\" must not move yesterday's expense to today");
    }

    @Test
    void resolve_letsAFreshValueWinOverTheCarriedOne() {
        User user = user("u1", "USD", "UTC");
        when(categoryRepository.findByUserIdAndStatus("u1", CategoryStatus.ACTIVE)).thenReturn(List.of(
                category("c-fuel", "Fuel", CategoryKind.EXPENSE)));

        ParsedIntent draft = resolver.resolve(user, "actually it was 30 on fuel",
                extraction(IntentType.CREATE_TRANSACTION, TransactionType.EXPENSE,
                        "30", "Fuel", "today", null, "fuel", 0.9),
                pending(TransactionType.EXPENSE, 2000L, null, 0.9));

        assertEquals(3000L, draft.getAmountMinor(), "a correction replaces, it does not merge underneath");
    }

    @Test
    void resolve_dropsACarriedCategoryTheNewDirectionInvalidates() {
        User user = user("u1", "USD", "UTC");
        when(categoryRepository.findByUserIdAndStatus("u1", CategoryStatus.ACTIVE)).thenReturn(List.of(
                category("c-food", "Food & Drinks", CategoryKind.EXPENSE),
                category("c-salary", "Salary", CategoryKind.INCOME)));

        ParsedIntent draft = resolver.resolve(user, "no, I received that",
                extraction(IntentType.CREATE_TRANSACTION, TransactionType.INCOME,
                        null, null, null, null, null, 0.9),
                pending(TransactionType.EXPENSE, 2000L, "c-food", 0.9));

        assertEquals(TransactionType.INCOME, draft.getTxnType());
        assertNull(draft.getCategoryId(), "an expense category cannot carry into an income draft");
        assertTrue(draft.getMissingFields().contains("category"));
    }

    @Test
    void resolve_believesTheModelWhenTheUserChangesTheSubject() {
        User user = user("u1", "USD", "UTC");

        ParsedIntent draft = resolver.resolve(user, "how much did I spend on food?",
                extraction(IntentType.QUERY, null, null, null, null, null, null, 0.9),
                pending(TransactionType.EXPENSE, 2000L, null, 0.9));

        assertEquals(IntentType.QUERY, draft.getIntent());
        assertTrue(draft.getMissingFields().contains("intent"));
    }

    // --- revalidate: one definition of "incomplete" ---

    @Test
    void revalidate_recomputesWhatIsMissingAfterTheUserEditsTheDraft() {
        ParsedIntent draft = pending(TransactionType.EXPENSE, 2000L, "c-food", 0.9);
        draft.getMissingFields().add("category");

        resolver.revalidate(draft);
        assertTrue(draft.isComplete(), "the category the edit supplied clears the question");

        draft.setAmountMinor(0L);
        resolver.revalidate(draft);
        assertEquals(List.of("amount"), draft.getMissingFields(), "a zero amount is no amount");
    }

    @Test
    void revalidate_asksAboutDirectionBeforeCategory() {
        ParsedIntent draft = pending(null, 2000L, null, 0.9);

        resolver.revalidate(draft);

        assertEquals(List.of("type"), draft.getMissingFields(),
                "which half of the category list to offer depends on the answer");
    }

    // --- an amount the message never contained ---

    /**
     * Observed live, and the reason this guard exists: qwen2.5:1.5b read "me and my
     * friend went to the movie yesterday" as {@code amount: "20"} and — with no
     * confidence gate left to stop it — that invented figure was written to the ledger.
     */
    @Test
    void refusesAnAmountWhenTheMessageCarriesNoDigit() {
        when(categoryRepository.findByUserIdAndStatus("u1", CategoryStatus.ACTIVE))
                .thenReturn(List.of(category("c-fun", "Entertainment", CategoryKind.EXPENSE)));
        LlmExtraction invented = extraction(IntentType.CREATE_TRANSACTION, TransactionType.EXPENSE,
                "20", "Entertainment", "yesterday", null, "movie", 0.95);

        ParsedIntent draft = resolver.resolve(user("u1", "USD", "UTC"),
                "me and my friend went to the movie yesterday", invented);

        assertNull(draft.getAmountMinor(), "the message names no number, so neither may the draft");
        assertTrue(draft.getMissingFields().contains("amount"),
                "which turns a fabricated row into the question it should have been");
    }

    @Test
    void stillReadsAnAmountTheMessageDoesCarry() {
        when(categoryRepository.findByUserIdAndStatus("u1", CategoryStatus.ACTIVE))
                .thenReturn(List.of(category("c-fun", "Entertainment", CategoryKind.EXPENSE)));
        LlmExtraction read = extraction(IntentType.CREATE_TRANSACTION, TransactionType.EXPENSE,
                "20", "Entertainment", "yesterday", null, "movie", 0.95);

        ParsedIntent draft = resolver.resolve(user("u1", "USD", "UTC"),
                "we went to the movie yesterday, 20 for tickets", read);

        assertEquals(2000L, draft.getAmountMinor());
    }

    /** An answer carries the amount forward even when this turn names no number. */
    @Test
    void keepsACarriedAmountFromAnEarlierTurn() {
        when(categoryRepository.findByUserIdAndStatus("u1", CategoryStatus.ACTIVE))
                .thenReturn(List.of(category("c-fun", "Entertainment", CategoryKind.EXPENSE)));
        ParsedIntent pending = pending(TransactionType.EXPENSE, 2000L, "c-fun", 0.9);
        LlmExtraction read = extraction(IntentType.CREATE_TRANSACTION, TransactionType.EXPENSE,
                null, "Entertainment", "yesterday", null, "movie", 0.9);

        ParsedIntent draft = resolver.resolve(user("u1", "USD", "UTC"), "it was entertainment", read, pending);

        assertEquals(2000L, draft.getAmountMinor(), "the earlier turn established it; this one need not repeat it");
    }

    // --- amount shapes people actually type ---

    @Test
    void readsAThousandsSeparatedAmount() {
        assertEquals(250_000L, IntentResolver.toMinorUnits("2,500", "USD"),
                "the prompt forbids the comma, but a 1.5B model writes it anyway");
        assertEquals(123_456L, IntentResolver.toMinorUnits("1,234.56", "USD"));
    }

    /**
     * The one that matters most. In French and Spanish "2,50" is two-fifty; stripping
     * that comma would record 250 — a hundredfold error on someone's money. Refusing it
     * turns a silent wrong row into a question.
     */
    @Test
    void refusesACommaThatIsNotAThousandsSeparator() {
        assertNull(IntentResolver.toMinorUnits("2,50", "USD"));
        assertNull(IntentResolver.toMinorUnits("2,5", "USD"));
        assertNull(IntentResolver.toMinorUnits("1,23,456", "USD"),
                "not the grouping this rule recognises — better asked than guessed");
    }

    @Test
    void expandsMagnitudeShorthand() {
        assertEquals(25_000_000L, IntentResolver.toMinorUnits("250k", "USD"));
        assertEquals(250_000L, IntentResolver.toMinorUnits("2.5k", "USD"));
        assertEquals(10_000_000L, IntentResolver.toMinorUnits("1 lakh", "USD"));
        assertEquals(1_000_000_000L, IntentResolver.toMinorUnits("1crore", "USD"));
        assertEquals(150_000_000L, IntentResolver.toMinorUnits("1.5m", "USD"));
    }

    /** Shorthand is text arithmetic, never a float — 2.5k has to be exactly 2500. */
    @Test
    void expandsShorthandWithoutLosingPrecision() {
        assertEquals("2500", IntentResolver.normalizeAmount("2.5k"));
        assertEquals("1250", IntentResolver.normalizeAmount("1.25k"));
        assertEquals("1234.5", IntentResolver.normalizeAmount("1.2345k"));
    }

    @Test
    void stillRefusesSomethingThatIsNotAnAmount() {
        assertNull(IntentResolver.toMinorUnits("a lot", "USD"));
        assertNull(IntentResolver.toMinorUnits("", "USD"));
        assertNull(IntentResolver.toMinorUnits("k", "USD"), "a bare suffix names no number");
        assertNull(IntentResolver.toMinorUnits("$5", "USD"), "a symbol is still the model ignoring the prompt");
        assertNull(IntentResolver.toMinorUnits("1 500", "USD"), "a space separator is still ambiguous");
    }

    // --- recurring: a rule, not a record ---

    @Test
    void readsARepeatPhraseAsATemplateEvenWhenTheModelSaidTransaction() {
        when(categoryRepository.findByUserIdAndStatus("u1", CategoryStatus.ACTIVE))
                .thenReturn(List.of(category("c-subs", "Subscriptions", CategoryKind.EXPENSE)));
        LlmExtraction read = extraction(IntentType.CREATE_TRANSACTION, TransactionType.EXPENSE,
                "15", "Subscriptions", "today", "Netflix", "Netflix", 0.94);

        ParsedIntent draft = resolver.resolve(user("u1", "USD", "UTC"), "Netflix 15 every month", read);

        assertEquals(IntentType.CREATE_RECURRING, draft.getIntent(),
                "a repeat captured as a one-off is the error that compounds every month");
        assertEquals(RecurringCadence.MONTHLY, draft.getCadence());
        assertTrue(draft.isComplete());
    }

    @Test
    void neverDemotesATemplateTheModelRead() {
        when(categoryRepository.findByUserIdAndStatus("u1", CategoryStatus.ACTIVE))
                .thenReturn(List.of(category("c-subs", "Subscriptions", CategoryKind.EXPENSE)));
        LlmExtraction read = extraction(IntentType.CREATE_RECURRING, TransactionType.EXPENSE,
                "15", "Subscriptions", "today", "Spotify", "Spotify", 0.9);
        read.setCadence(RecurringCadence.MONTHLY);

        ParsedIntent draft = resolver.resolve(user("u1", "USD", "UTC"), "my Spotify subscription is 15", read);

        assertEquals(IntentType.CREATE_RECURRING, draft.getIntent(),
                "the model saw the whole sentence; the backend only ever promotes");
        assertEquals(RecurringCadence.MONTHLY, draft.getCadence());
    }

    @Test
    void leavesAnAdjectiveAloneRatherThanBillingTheUserForever() {
        when(categoryRepository.findByUserIdAndStatus("u1", CategoryStatus.ACTIVE))
                .thenReturn(List.of(category("c-transport", "Transport", CategoryKind.EXPENSE)));
        LlmExtraction read = extraction(IntentType.CREATE_TRANSACTION, TransactionType.EXPENSE,
                "50", "Transport", "today", null, "monthly bus pass", 0.9);

        ParsedIntent draft = resolver.resolve(user("u1", "USD", "UTC"), "I paid 50 for a monthly bus pass", read);

        assertEquals(IntentType.CREATE_TRANSACTION, draft.getIntent(),
                "\"monthly\" here describes the pass, not the payment — reading it in context is the model's job");
    }

    @Test
    void resolveCadence_prefersAPhraseTheUserTypedOverTheModelsField() {
        assertEquals(RecurringCadence.WEEKLY,
                IntentResolver.resolveCadence(RecurringCadence.MONTHLY, "the gym, every week"),
                "the same precedence as the direction, and for the same reason");
    }

    @Test
    void resolveCadence_fallsBackToTheModelWhenTheMessageNamesNoPeriod() {
        assertEquals(RecurringCadence.YEARLY,
                IntentResolver.resolveCadence(RecurringCadence.YEARLY, "my insurance renewal"));
    }

    @Test
    void resolveCadence_answersNullWhenNeitherSourceNamesOne() {
        assertNull(IntentResolver.resolveCadence(null, "my Spotify subscription"),
                "monthly would be a rule the user never stated");
    }

    @Test
    void resolveCadence_mapsEveryPeriodItAdvertises() {
        assertEquals(RecurringCadence.DAILY, IntentResolver.resolveCadence(null, "coffee every day"));
        assertEquals(RecurringCadence.WEEKLY, IntentResolver.resolveCadence(null, "cleaner, weekly"));
        assertEquals(RecurringCadence.MONTHLY, IntentResolver.resolveCadence(null, "rent every month"));
        assertEquals(RecurringCadence.YEARLY, IntentResolver.resolveCadence(null, "domain renewal annually"));
    }

    @Test
    void looksRecurring_ignoresABareAdverb() {
        assertTrue(IntentResolver.looksRecurring("Netflix 15 every month"));
        assertFalse(IntentResolver.looksRecurring("I bought a monthly bus pass"),
                "only the every|each forms are unambiguous enough to promote on");
    }

    @Test
    void revalidate_asksHowOftenARecurringDraftRepeats() {
        ParsedIntent draft = pending(TransactionType.EXPENSE, 1500L, "c-subs", 0.9);
        draft.setIntent(IntentType.CREATE_RECURRING);

        resolver.revalidate(draft);

        assertEquals(List.of("cadence"), draft.getMissingFields());

        draft.setCadence(RecurringCadence.MONTHLY);
        resolver.revalidate(draft);
        assertTrue(draft.isComplete());
    }

    @Test
    void revalidate_doesNotAskACadenceOfAOneOff() {
        ParsedIntent draft = pending(TransactionType.EXPENSE, 1500L, "c-food", 0.9);

        resolver.revalidate(draft);

        assertTrue(draft.isComplete(), "a transaction has no frequency to ask about");
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

    /** A draft an earlier turn of the same conversation left open. */
    private static ParsedIntent pending(TransactionType type, Long amountMinor,
                                        String categoryId, double confidence) {
        ParsedIntent draft = new ParsedIntent();
        draft.setIntent(IntentType.CREATE_TRANSACTION);
        draft.setTxnType(type);
        draft.setAmountMinor(amountMinor);
        draft.setCurrency("USD");
        draft.setCategoryId(categoryId);
        draft.setConfidence(confidence);
        return draft;
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
