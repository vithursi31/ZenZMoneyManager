package com.zenzmoney.common.domain;

/**
 * Where a chat turn sits in the capture pipeline (chat entry plan §5.1).
 *
 * <p>The write gate lives in this enum: only a {@link #PARSED} draft can be
 * confirmed into the ledger, and confirming moves it to {@link #CONFIRMED} —
 * which is terminal, so a draft can never be written twice (§9). A conversation
 * carries at most one confirmable draft: refining or replacing it moves the older
 * turn to {@link #SUPERSEDED}.
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

    /**
     * A question the assistant answered from the user's own ledger figures (F-1.16).
     * Terminal and carries no draft — reading the ledger to answer never writes to it.
     */
    ANSWERED,

    /**
     * A later turn carries the draft this one used to hold — the user answered a
     * question, edited the draft, or said something new. Terminal; nothing was
     * written, and it exists so a corrected draft cannot leave its pre-correction
     * self confirmable behind it.
     */
    SUPERSEDED,

    /** The model was unreachable or its output unreadable. Distinct from a confident UNKNOWN. */
    FAILED
}
