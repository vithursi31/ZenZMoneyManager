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
 * the category list is a closed set, and the rules the backend depends on: no invented
 * currency, no absolute dates, and the array output shape the client parses. Wording
 * is free to change; these properties are not.
 */
class ExtractionPromptTest {

    /** The prompt the application actually ships, not a fixture. */
    private final ExtractionPrompt prompt =
            new ExtractionPrompt(new ClassPathResource("prompts/extraction-system.md"));

    @Test
    void listsEveryCategoryTheUserOwns() {
        String system = prompt.system(List.of("Food & Drinks", "Groceries", "Salary"), null);

        assertTrue(system.contains("- Food & Drinks"));
        assertTrue(system.contains("- Groceries"));
        assertTrue(system.contains("- Salary"));
    }

    @Test
    void skipsBlankAndMissingCategoryNames() {
        String system = prompt.system(Arrays.asList("Groceries", "  ", null), null);

        assertTrue(system.contains("- Groceries"));
        assertFalse(system.contains("- \n"), "a blank name must not become an empty bullet");
    }

    @Test
    void tellsTheModelToGuessNothingWhenTheUserHasNoCategories() {
        String system = prompt.system(List.of(), null);

        assertTrue(system.contains("(none)"), "the empty list marker the template's rule keys off");
        assertTrue(system.contains("`categoryGuess` is `null`"),
                "with no list to copy from, every guess has to be null");
    }

    @Test
    void keepsCurrencyAndAbsoluteDatesOutOfTheModelsHands() {
        String system = prompt.system(List.of("Groceries"), null);

        assertTrue(system.contains("Never output a currency, and never an absolute date"),
                "the two things the backend owns and a model cannot know");
        assertTrue(system.contains("as the user phrased it"),
                "the model emits a date phrase, the backend resolves it");
    }

    /**
     * The client parses {@code {"intent":…, "items":[…]}}. A prompt that stopped asking
     * for the array would degrade every multi-entry message to one entry, silently.
     */
    @Test
    void asksForTheArrayShapeTheClientParses() {
        String system = prompt.system(List.of("Groceries"), null);

        assertTrue(system.contains("\"items\""));
        assertTrue(system.contains("One item per distinct amount"));
    }

    /**
     * The most expensive misreading available: a removal read as a capture *adds* what
     * the user asked to take away, and answers "Added to your ledger" while doing it.
     */
    @Test
    void tellsTheModelThatRemovingIsNotRecording() {
        String system = prompt.system(List.of("Groceries"), null);

        assertTrue(system.contains("Removing is not recording"));
        assertTrue(system.contains("DELETE_TRANSACTION"));
        assertTrue(system.contains("DELETE_RECURRING"));
    }

    /** A repeat read as a one-off is the error that compounds; the rule has to be there. */
    @Test
    void tellsTheModelToReadARepeatAsATemplate() {
        String system = prompt.system(List.of("Subscriptions"), null);

        assertTrue(system.contains("RECURRING"));
        assertTrue(system.contains("cadence"));
    }

    @Test
    void endsWithTheValidationProtocolThatChecksEveryRule() {
        String system = prompt.system(List.of("Groceries"), null);

        assertTrue(system.contains("Final Validation Protocol (MANDATORY)"),
                "the self-check is what turns the rules above from advice into a pass");
    }

    /** "one ticket 50 for snacks 10" is unreadable without the turn that preceded it. */
    @Test
    void carriesTheConversationWhenThereIsOne() {
        String system = prompt.system(List.of("Groceries"),
                "user: me and my friend went to the movie\nassistant: How much did you spend?");

        assertTrue(system.contains("Conversation So Far"));
        assertTrue(system.contains("went to the movie"), "the turn that gives the answer meaning");
        assertTrue(system.contains("How much did you spend?"));
    }

    @Test
    void carriesNoConversationBlockOnAFreshMessage() {
        assertFalse(prompt.system(List.of("Groceries"), null).contains("Conversation So Far"),
                "a new conversation has nothing to look back at");
    }

    @Test
    void leavesNoPlaceholderBehind() {
        assertFalse(prompt.system(List.of("Groceries"), null).contains("{{"),
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
