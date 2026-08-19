package com.zenzmoney.core.service.llm;

import com.zenzmoney.core.service.insight.SpendingSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Currency;

/**
 * The answering contract sent to the model (F-1.16), with the user's own figures
 * substituted in. Wording lives in {@code resources/prompts/advice-system.md} for
 * the same reason the extraction prompt does — it is content that gets tuned, and a
 * markdown file reads in a diff.
 *
 * <p><b>This is the one place the backend renders money as text, and it is not a
 * client-formatting job.</b> The model has to read magnitudes to reason about them,
 * and "2000" in a currency with two decimals is twenty, not two thousand — a model
 * given minor units writes confidently wrong advice. So amounts go in as major units
 * with the ISO code ("20.00 USD"), never localized and never a symbol: this is input
 * to a model, not output to a person. The reply the client renders alongside still
 * carries minor units (§0.2).
 */
@Component
class AdvicePrompt {

    private static final String SNAPSHOT_TOKEN = "{{snapshot}}";

    /** Currencies with no minor unit still need a divisor; matches the resolver's default. */
    private static final int DEFAULT_FRACTION_DIGITS = 2;

    private final String template;

    AdvicePrompt(@Value("classpath:prompts/advice-system.md") Resource promptFile) {
        this.template = read(promptFile);
        if (!this.template.contains(SNAPSHOT_TOKEN)) {
            throw new IllegalStateException(
                    promptFile.getDescription() + " is missing the " + SNAPSHOT_TOKEN + " placeholder");
        }
    }

    String system(SpendingSnapshot snapshot) {
        return template.replace(SNAPSHOT_TOKEN, render(snapshot));
    }

    /**
     * The snapshot as a compact, unambiguous block. Deliberately plain lines rather
     * than JSON: a small instruct model follows figures it can read straight down the
     * page, and every number here is one it may quote verbatim.
     */
    static String render(SpendingSnapshot snapshot) {
        String currency = snapshot.getCurrency() == null ? "" : snapshot.getCurrency();
        StringBuilder out = new StringBuilder(512);
        out.append("Currency: ").append(currency.isEmpty() ? "unknown" : currency).append('\n');

        for (SpendingSnapshot.MonthSpend month : snapshot.getMonths()) {
            out.append('\n').append("Month ").append(month.getMonth()).append('\n')
                    .append("- Total income: ").append(money(month.getIncome(), currency)).append('\n')
                    .append("- Total expenses: ").append(money(month.getExpenses(), currency)).append('\n')
                    .append("- Left over: ").append(money(month.getPosition(), currency)).append('\n');
            if (month.getCategories().isEmpty()) {
                out.append("- Expenses by category: none recorded\n");
                continue;
            }
            out.append("- Expenses by category, largest first:\n");
            for (SpendingSnapshot.CategorySpend category : month.getCategories()) {
                out.append("  - ").append(category.getName()).append(": ")
                        .append(money(category.getAmount(), currency)).append('\n');
            }
        }
        return out.toString().stripTrailing();
    }

    /** Minor units to a plain major-unit decimal plus the ISO code — exact, never a float. */
    private static String money(long minor, String currency) {
        BigDecimal major = BigDecimal.valueOf(minor, fractionDigits(currency))
                .setScale(fractionDigits(currency), RoundingMode.UNNECESSARY);
        return currency.isEmpty() ? major.toPlainString() : major.toPlainString() + " " + currency;
    }

    private static int fractionDigits(String currencyCode) {
        try {
            int digits = Currency.getInstance(currencyCode).getDefaultFractionDigits();
            return digits < 0 ? DEFAULT_FRACTION_DIGITS : digits;
        } catch (IllegalArgumentException | NullPointerException e) {
            return DEFAULT_FRACTION_DIGITS;
        }
    }

    private static String read(Resource promptFile) {
        try {
            return promptFile.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Without the prompt every question would degrade to "I couldn't work that
            // out", so fail at startup where the cause is obvious.
            throw new UncheckedIOException("Cannot read " + promptFile.getDescription(), e);
        }
    }
}
