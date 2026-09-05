package com.zenzmoney.common.domain;

/**
 * Where a chat turn sits in the capture pipeline (chat entry plan §5.1).
 *
 * <p>The write gate lives in this enum. A draft the model read confidently and
 * completely is written straight to the ledger as {@link #CREATED}; one it was
 * unsure of stops at {@link #PARSED} and needs an explicit confirm, which lands on
 * {@link #CONFIRMED}. Both are terminal for writing — a draft can never be written
 * twice (§9) — and both can still be reversed to {@link #UNDONE}. A conversation
 * carries at most one open draft: refining or replacing it moves the older turn to
 * {@link #SUPERSEDED}.
 */
public enum ChatMessageStatus {

    /** A user turn, logged as received. Carries no draft. */
    RECEIVED,

    /**
     * Written to the ledger directly, because nothing was missing and the model was
     * confident. Carries the transaction or recurring id, and is reversible until the
     * user moves on — {@link #UNDONE} is the way back.
     */
    CREATED,

    /** A draft the model doubted, awaiting the user's confirmation. The only confirmable state. */
    PARSED,

    /** Something was missing or the model was unsure; the assistant asked a question. */
    NEEDS_CLARIFICATION,

    /** A doubted draft the user confirmed into the ledger. Carries the transaction id. */
    CONFIRMED,

    /**
     * This turn removed a transaction the user already had (F-1.11). Terminal — and
     * still reversible, because deleting is soft: undoing it restores the row.
     */
    REMOVED,

    /**
     * The row this turn wrote has been deleted at the user's request. Terminal, and
     * the reason chat may write without asking first: the way back is one tap, so a
     * misread amount is a correction rather than a wrong row the user must hunt down.
     */
    UNDONE,

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
