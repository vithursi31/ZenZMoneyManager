package com.zenzmoney.core.service.llm;

import com.zenzmoney.common.domain.IntentType;
import com.zenzmoney.common.domain.RecurringCadence;
import com.zenzmoney.common.domain.TransactionType;
import lombok.Getter;
import lombok.Setter;

/**
 * The model's raw reading of <b>one money event</b> — everything the model is allowed
 * to decide about it, and nothing more (F-1.11 / §6). A message naming
 * three amounts yields three of these, carried in an {@link LlmExtractionBatch}.
 *
 * <p>This is deliberately <em>pre-normalization</em>: no ids, no minor units, no
 * timestamps. {@code IntentResolver} turns it into a domain {@code ParsedIntent}
 * by resolving {@link #categoryGuess} against the user's categories,
 * {@link #dateExpr} against their timezone, and {@link #amountRaw} against their
 * active currency. The model never sees or invents any of those.
 *
 * <p><b>Why {@link #amountRaw} is a String.</b> It is the amount in <em>major</em>
 * units exactly as the model wrote it ("5", "15.50"). Money never travels through
 * a float in this codebase, so the text goes straight to minor units in the
 * resolver — there is no intermediate {@code double} for a rounding error to hide
 * in. It is also unvalidated: the model may write anything, and rejecting it is
 * the resolver's job.
 */
@Getter
@Setter
public class LlmExtraction {

    /**
     * Never null — {@link IntentType#UNKNOWN} when the model gave nothing usable.
     * Copied down from the batch: the model classifies the <em>message</em>, and a
     * per-item {@code kind} is what separates a one-off from a template within it.
     */
    private IntentType intent = IntentType.UNKNOWN;

    /**
     * The repeat frequency, when the model read one. Null on a one-off, and also null
     * on a repeat whose frequency the user never named ("my Spotify subscription") —
     * which is a question to ask, not a monthly assumption to make.
     */
    private RecurringCadence cadence;

    /** True when the model read this event as a rule that repeats rather than money that moved. */
    private boolean recurring;

    /** Null when the model could not tell income from expense. */
    private TransactionType txnType;

    /** Amount in major units as written by the model, e.g. "15.50". Null if absent. */
    private String amountRaw;

    /** A category <em>name</em> the model picked from the user's list. Null if none fits. */
    private String categoryGuess;

    /** The date as the user phrased it ("today", "yesterday"). Never an absolute date (§6). */
    private String dateExpr;

    /** Merchant or person named in the message ("Keells"). Null when none is named. */
    private String payee;

    /** What the money was for ("burger", "tea things"). Null when the user said nothing. */
    private String note;

    /** The model's own 0.0–1.0 certainty; drives the clarification branch (§5.4). */
    private double confidence;

    /**
     * True when the model could not be reached or its output could not be read.
     * Distinct from a confident {@code UNKNOWN}: the chat flow answers
     * "I couldn't read that" rather than asking a clarifying question (§9).
     */
    private boolean failed;

    /** The result for every failure path — the client never throws to the caller. */
    public static LlmExtraction failed() {
        LlmExtraction extraction = new LlmExtraction();
        extraction.setIntent(IntentType.UNKNOWN);
        extraction.setFailed(true);
        return extraction;
    }
}
