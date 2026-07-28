package com.zenzmoney.core.service.llm;

import java.util.List;

/**
 * Reads a natural-language capture message into a structured {@link LlmExtraction}
 * (chat entry plan §5.3). The seam that keeps the rest of the chat pipeline free of
 * any one model or vendor — swapping Ollama for something else is a new
 * implementation of this interface and a config change.
 */
public interface LlmExtractionClient {

    /**
     * Extracts one transaction from {@code message}.
     *
     * @param message       what the user typed, verbatim.
     * @param categoryNames the caller's own category names, offered to the model as
     *                      the closed list it may guess from. Only names — no ids,
     *                      no amounts, and never another user's data (§9 privacy).
     * @return the extraction; never null and <b>never throws</b>. A timeout, a down
     *         model, or unreadable output all come back as
     *         {@link LlmExtraction#failed()} so the chat flow degrades to a reply
     *         instead of a 5xx (§9).
     */
    LlmExtraction extract(String message, List<String> categoryNames);
}
