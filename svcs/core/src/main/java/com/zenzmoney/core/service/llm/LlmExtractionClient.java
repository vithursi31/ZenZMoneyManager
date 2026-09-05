package com.zenzmoney.core.service.llm;

import java.util.List;

/**
 * Reads a natural-language capture message into a structured
 * {@link LlmExtractionBatch} (F-1.11). The seam that keeps the rest of
 * the chat pipeline free of any one model or vendor — swapping Ollama for something
 * else is a new implementation of this interface and a config change.
 */
public interface LlmExtractionClient {

    /**
     * Extracts every money event named in {@code message} — usually one, sometimes
     * several ("$28 on coffee, $350 on groceries").
     *
     * @param message         what the user typed, verbatim.
     * @param categoryNames   the caller's own category names, offered to the model as
     *                        the closed list it may guess from. Only names — no ids,
     *                        no amounts, and never another user's data (§9 privacy).
     * @param pendingQuestion the question the assistant asked just before this message,
     *                        or null when the message opens a conversation. It is the
     *                        difference between reading "20" as noise and reading it as
     *                        an answer, and it is the only conversation state the model
     *                        is given — the draft itself stays on the backend, where
     *                        merging it is deterministic.
     * @return the extraction; never null and <b>never throws</b>. A timeout, a down
     *         model, or unreadable output all come back as
     *         {@link LlmExtractionBatch#failed()} so the chat flow degrades to a reply
     *         instead of a 5xx (§9).
     */
    LlmExtractionBatch extract(String message, List<String> categoryNames, String pendingQuestion);
}
