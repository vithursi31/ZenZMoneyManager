package com.zenzmoney.core.service.llm;

import com.zenzmoney.core.service.insight.SpendingSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Everything the model is allowed to say about the user's money passes through this
 * rendering, so two things are pinned: the amounts are exact, and nothing beyond
 * aggregates reaches the prompt.
 */
class AdvicePromptTest {

    private static final AdvicePrompt PROMPT =
            new AdvicePrompt(new ClassPathResource("prompts/advice-system.md"));

    @Test
    void rendersMinorUnitsAsExactMajorUnitsWithTheIsoCode() {
        String rendered = AdvicePrompt.render(snapshot("USD",
                month("2026-08", 500_000L, 123_456L, spend("Food & Drinks", 45_005L))));

        assertTrue(rendered.contains("Total income: 5000.00 USD"));
        assertTrue(rendered.contains("Total expenses: 1234.56 USD"));
        assertTrue(rendered.contains("Food & Drinks: 450.05 USD"),
                "a model handed \"45005\" writes advice about forty-five thousand");
    }

    /**
     * The classic float casualties, rendered from integers. A tenth of a unit that
     * came out as 0.099999 would be quoted verbatim by the model into the user's face.
     */
    @Test
    void rendersAmountsAFloatWouldHaveMangled() {
        String rendered = AdvicePrompt.render(snapshot("USD",
                month("2026-08", 10L, 29L, spend("Transport", 107L))));

        assertTrue(rendered.contains("0.10 USD"));
        assertTrue(rendered.contains("0.29 USD"));
        assertTrue(rendered.contains("1.07 USD"));
    }

    @Test
    void honoursACurrencyWithNoMinorUnit() {
        String rendered = AdvicePrompt.render(snapshot("JPY",
                month("2026-08", 0L, 1500L, spend("Groceries", 1500L))));

        assertTrue(rendered.contains("1500 JPY"), "¥1500 is 1500 minor units, not 15.00");
        assertFalse(rendered.contains("15.00"));
    }

    @Test
    void reportsTheLeftOverAsIncomeMinusExpensesIncludingADeficit() {
        String rendered = AdvicePrompt.render(snapshot("USD",
                month("2026-08", 100_000L, 150_000L, spend("Rent", 150_000L))));

        assertTrue(rendered.contains("Left over: -500.00 USD"),
                "a month can legitimately run at a deficit (§1.10)");
    }

    @Test
    void saysSoWhenAMonthHasNoCategorisedSpend() {
        String rendered = AdvicePrompt.render(snapshot("USD", month("2026-08", 0L, 0L)));

        assertTrue(rendered.contains("none recorded"));
    }

    @Test
    void survivesAUserWhoHasNoCurrencyYet() {
        String rendered = AdvicePrompt.render(snapshot(null, month("2026-08", 0L, 500L)));

        assertTrue(rendered.contains("Currency: unknown"));
        assertFalse(rendered.contains("null"), "a literal null in the prompt is a figure the model will quote");
    }

    @Test
    void putsTheFiguresIntoTheTemplateAndLeavesNoPlaceholderBehind() {
        String system = PROMPT.system(snapshot("USD",
                month("2026-08", 0L, 2000L, spend("Fuel", 2000L))));

        assertTrue(system.contains("Never invent a number"), "the grounding rule must reach the model");
        assertTrue(system.contains("Fuel: 20.00 USD"));
        assertFalse(system.contains("{{"), "an unsubstituted placeholder is a prompt bug the model reads as text");
    }

    @Test
    void failsAtStartupWhenTheTemplateLostItsPlaceholder() {
        assertThrows(IllegalStateException.class,
                () -> new AdvicePrompt(new ClassPathResource("prompts/extraction-system.md")),
                "a prompt with nowhere to put the figures would answer every question from nothing");
    }

    // --- fixtures ---

    private static SpendingSnapshot snapshot(String currency, SpendingSnapshot.MonthSpend... months) {
        return new SpendingSnapshot(currency, "UTC", List.of(months));
    }

    private static SpendingSnapshot.MonthSpend month(String month, long income, long expenses,
                                                     SpendingSnapshot.CategorySpend... categories) {
        return new SpendingSnapshot.MonthSpend(month, income, expenses, List.of(categories));
    }

    private static SpendingSnapshot.CategorySpend spend(String name, long amount) {
        return new SpendingSnapshot.CategorySpend("c-" + name, name, amount);
    }
}
