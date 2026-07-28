package com.zenzmoney.core.service.llm;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the structural parts of the prompt — that the real markdown file loads, that
 * the category list is a closed set, and the two rules the backend depends on (no
 * invented currency, no absolute dates). Wording is free to change; these properties
 * are not.
 */
class ExtractionPromptTest {

    /** The prompt the application actually ships, not a fixture. */
    private final ExtractionPrompt prompt =
            new ExtractionPrompt(new ClassPathResource("prompts/extraction-system.md"));

    @Test
    void listsEveryCategoryTheUserOwns() {
        String system = prompt.system(List.of("Food & Drinks", "Groceries", "Salary"));

        assertTrue(system.contains("- Food & Drinks"));
        assertTrue(system.contains("- Groceries"));
        assertTrue(system.contains("- Salary"));
    }

    @Test
    void skipsBlankAndMissingCategoryNames() {
        String system = prompt.system(Arrays.asList("Groceries", "  ", null));

        assertTrue(system.contains("- Groceries"));
        assertFalse(system.contains("- \n"), "a blank name must not become an empty bullet");
    }

    @Test
    void tellsTheModelToGuessNothingWhenTheUserHasNoCategories() {
        String system = prompt.system(List.of());

        assertTrue(system.contains("(none)"), "the empty list marker the template's rule keys off");
        assertTrue(system.contains("categoryGuess to null"));
    }

    @Test
    void keepsCurrencyAndAbsoluteDatesOutOfTheModelsHands() {
        String system = prompt.system(List.of("Groceries"));

        assertTrue(system.contains("Never infer or output a currency"));
        assertTrue(system.contains("never compute or"), "the model emits a date phrase, the backend resolves it");
    }

    @Test
    void leavesNoPlaceholderBehind() {
        assertFalse(prompt.system(List.of("Groceries")).contains("{{"),
                "an unsubstituted placeholder would be sent to the model verbatim");
    }

    /**
     * A prompt file edited to drop the placeholder would silently send every user the
     * same category-less prompt, so it fails at construction — i.e. at boot.
     */
    @Test
    void rejectsAPromptFileWithNoCategoryPlaceholder() {
        ByteArrayResource noPlaceholder = new ByteArrayResource("Extract a transaction.".getBytes());

        assertThrows(IllegalStateException.class, () -> new ExtractionPrompt(noPlaceholder));
    }
}
