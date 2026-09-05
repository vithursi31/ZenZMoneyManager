package com.zenzmoney.core.service.llm;

import com.zenzmoney.common.domain.IntentType;
import lombok.Getter;

import java.util.List;

/**
 * Everything the model read from one chat message: what the message was *for*, and
 * one {@link LlmExtraction} per distinct money event in it.
 *
 * <p><b>Why a batch at all.</b> "I spent $28 on coffee, $350 on groceries and $120
 * on fuel" is three entries, and only the model can do the amount-to-noun pairing
 * that splits them. Extracting one and dropping the rest silently loses two thirds
 * of what the user said, which is worse than asking.
 *
 * <p>The list is empty for a {@link IntentType#QUERY} or a failed reading — the
 * intent alone routes those. It is never null.
 */
@Getter
public class LlmExtractionBatch {

    private final IntentType intent;

    private final List<LlmExtraction> items;

    /**
     * True when the model could not be reached or its output could not be read.
     * Distinct from a confident {@code UNKNOWN}: the chat flow answers "I couldn't
     * read that" rather than asking a clarifying question (§9).
     */
    private final boolean failed;

    private LlmExtractionBatch(IntentType intent, List<LlmExtraction> items, boolean failed) {
        this.intent = intent == null ? IntentType.UNKNOWN : intent;
        this.items = items == null ? List.of() : List.copyOf(items);
        this.failed = failed;
    }

    public static LlmExtractionBatch of(IntentType intent, List<LlmExtraction> items) {
        return new LlmExtractionBatch(intent, items, false);
    }

    /** The result for every failure path — the client never throws to the caller. */
    public static LlmExtractionBatch failed() {
        return new LlmExtractionBatch(IntentType.UNKNOWN, List.of(), true);
    }

    /** True when there is nothing to capture — a question, a refusal, or a failure. */
    public boolean isEmpty() {
        return items.isEmpty();
    }

    /**
     * The first item, or a failed stand-in. For the paths that are single-item by
     * nature — answering a pending question, and the resolver's own fallbacks.
     */
    public LlmExtraction first() {
        return items.isEmpty() ? LlmExtraction.failed() : items.get(0);
    }
}
