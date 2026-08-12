package com.zenzmoney.core.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenzmoney.common.domain.IntentType;
import com.zenzmoney.common.domain.TransactionType;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * A resolved draft transaction — what the backend made of the model's reading
 * (chat entry plan §3.3 / §5.4). Not a table: serialized to the
 * {@code chat_message.parsed_intent} jsonb column.
 *
 * <p>This is the other side of {@code LlmExtraction}: every field the model left
 * as language has been turned into data the ledger can accept — text amount into
 * minor units of the user's currency, date phrase into epoch millis in the user's
 * timezone, category name into a real category id.
 *
 * <p>{@link #payeeName} stays a <em>name</em> on purpose (§5.7). No {@code Payee}
 * row is created until the user confirms, so editing a draft can't litter the
 * user's payee list with rows they never accepted.
 */
@Getter
@Setter
public class ParsedIntent {

    private IntentType intent = IntentType.UNKNOWN;

    private TransactionType txnType;

    /** Minor units in {@link #currency}. Null when the amount couldn't be read. */
    private Long amountMinor;

    /** The user's active currency — never taken from the message text (§3.3). */
    private String currency;

    /** A real category id belonging to the user, or null when nothing matched. */
    private String categoryId;

    /** The model's raw category label, kept so a mismatch is diagnosable. */
    private String categoryGuess;

    /** Epoch millis, resolved from the model's date phrase in the user's timezone. */
    private Long txnDate;

    /** Merchant/person name; resolved to a Payee only on confirm (§5.7). */
    private String payeeName;

    private String note;

    private double confidence;

    /** Field names the draft still needs; non-empty means it can't be confirmed. */
    private List<String> missingFields = new ArrayList<>();

    /**
     * True when nothing is missing — the precondition for a confirmable draft.
     *
     * <p>Not persisted. It is derived from {@link #missingFields}, and Hibernate
     * round-trips this object through its <em>own</em> {@link ObjectMapper} to
     * snapshot the jsonb column — one that fails on unknown properties, unlike
     * Spring's. A derived getter with no matching setter therefore breaks the
     * insert, not just a later read.
     */
    @JsonIgnore
    public boolean isComplete() {
        return missingFields.isEmpty();
    }
}
