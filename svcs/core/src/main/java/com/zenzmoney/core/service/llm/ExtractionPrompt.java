package com.zenzmoney.core.service.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The extraction contract sent to the model (chat entry plan §6). The wording lives
 * in {@code resources/prompts/extraction-system.md}, not in this class: the prompt is
 * <em>content</em> that gets tuned against the eval fixtures, and a markdown file is
 * both readable in a diff and structured the way instruct models follow best.
 *
 * <p>The split it enforces: the model reads <em>language</em>, the backend owns
 * <em>data</em>. So the prompt forbids the two things a language model is bad at
 * and the backend is exact about — currency and absolute dates.
 *
 * <p>A bean rather than a static helper so a missing or unreadable prompt file fails
 * the <b>boot</b>. Loaded once: re-reading a classpath resource per chat message
 * would be IO on every request, and the file cannot change inside a running jar.
 */
@Component
class ExtractionPrompt {

    /** Where the user's category list is substituted into the template. */
    private static final String CATEGORIES_TOKEN = "{{categories}}";

    /** What the template's "set categoryGuess to null" rule keys off. */
    private static final String NO_CATEGORIES = "(none)";

    /** Where the question the assistant just asked is substituted in. */
    private static final String FOLLOW_UP_TOKEN = "{{followUp}}";

    private final String template;

    ExtractionPrompt(@Value("classpath:prompts/extraction-system.md") Resource promptFile) {
        this.template = read(promptFile);
        for (String token : List.of(CATEGORIES_TOKEN, FOLLOW_UP_TOKEN)) {
            if (!this.template.contains(token)) {
                throw new IllegalStateException(
                        promptFile.getDescription() + " is missing the " + token + " placeholder");
            }
        }
    }

    /**
     * Builds the system prompt for a user whose categories are {@code categoryNames}.
     * The list is a closed set: the model copies a name from it or answers null, so
     * the resolver matches against real rows instead of invented labels.
     */
    String system(List<String> categoryNames, String pendingQuestion) {
        return template
                .replace(CATEGORIES_TOKEN, categoryBlock(categoryNames))
                .replace(FOLLOW_UP_TOKEN, followUpBlock(pendingQuestion));
    }

    /**
     * Tells the model the message it is about to read is an answer, not an opening.
     * Without it a reply of "20" reads as nothing at all; the backend still owns the
     * merge, so the worst a bad reading costs here is one more question.
     */
    private static String followUpBlock(String pendingQuestion) {
        if (pendingQuestion == null || pendingQuestion.isBlank()) {
            return "";
        }
        return """

                ## Follow-up

                You already asked the user: "%s"
                Their message is most likely the answer to that question: read
                whatever it gives you and leave every other field null, because the
                rest is already known. But if it is plainly something else — a
                question about their money, or a different transaction — classify it
                for what it is instead."""
                .formatted(pendingQuestion.trim());
    }

    private static String categoryBlock(List<String> categoryNames) {
        if (categoryNames == null || categoryNames.isEmpty()) {
            return NO_CATEGORIES;
        }
        StringBuilder block = new StringBuilder(256);
        for (String name : categoryNames) {
            if (name != null && !name.isBlank()) {
                block.append("- ").append(name.trim()).append('\n');
            }
        }
        return block.isEmpty() ? NO_CATEGORIES : block.toString().stripTrailing();
    }

    private static String read(Resource promptFile) {
        try {
            return promptFile.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Without the prompt every extraction would silently degrade to "I couldn't
            // read that", so fail at startup where the cause is obvious.
            throw new UncheckedIOException("Cannot read " + promptFile.getDescription(), e);
        }
    }
}
