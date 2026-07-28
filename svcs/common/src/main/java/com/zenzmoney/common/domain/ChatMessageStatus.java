package com.zenzmoney.common.domain;

/**
 * Where a chat turn sits in the capture pipeline (chat entry plan §5.1).
 *
 * <p>The write gate lives in this enum: only a {@link #PARSED} draft can be
 * confirmed into the ledger, and confirming moves it to {@link #CONFIRMED} —
 * which is terminal, so a draft can never be written twice (§9).
 */
public enum ChatMessageStatus {

    /** A user turn, logged as received. Carries no draft. */
    RECEIVED,

    /** A complete draft awaiting the user's confirmation. The only confirmable state. */
    PARSED,

    /** Something was missing or the model was unsure; the assistant asked a question. */
    NEEDS_CLARIFICATION,

    /** The draft was written to the ledger. Terminal — carries the transaction id. */
    CONFIRMED,

    /** The user discarded the draft. Terminal; nothing was written. */
    REJECTED,

    /** The model was unreachable or its output unreadable. Distinct from a confident UNKNOWN. */
    FAILED
}
