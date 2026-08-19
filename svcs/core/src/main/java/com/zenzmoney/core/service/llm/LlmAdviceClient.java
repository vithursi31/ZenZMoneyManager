package com.zenzmoney.core.service.llm;

import com.zenzmoney.core.service.insight.SpendingSnapshot;

/**
 * Turns a question about the user's money, plus the figures that answer it, into a
 * sentence (F-1.16). The second of the two model passes chat makes: the first reads
 * <em>what the user wants</em>, this one writes <em>the reply</em>.
 *
 * <p>The seam exists for the same reason {@link LlmExtractionClient} does — the rest
 * of the app should not know which model is behind it. The two are separate
 * interfaces because they are separate jobs: one is constrained extraction returning
 * JSON, the other is grounded prose, and a single method taking a mode flag would
 * hide that.
 */
public interface LlmAdviceClient {

    /**
     * Answers {@code question} using only {@code snapshot}.
     *
     * @return the answer text, or <b>null</b> when the model was unreachable or said
     *         nothing usable. Never throws: an unavailable model costs the user an
     *         apology, not a 5xx (§9).
     */
    String answer(String question, SpendingSnapshot snapshot);
}
