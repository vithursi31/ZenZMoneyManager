package com.zenzmoney.common.domain;

/**
 * What the user's chat message is asking for, as read by the extraction model
 * (chat entry plan §5.1). {@link #CREATE_TRANSACTION} and {@link #CREATE_RECURRING}
 * are acted on; the rest are recognised so the pipeline can decline them explicitly
 * instead of mis-reading them as a capture.
 */
public enum IntentType {

    /** "I spent $5 for burger" — record a new income or expense. */
    CREATE_TRANSACTION,

    /** "Netflix 15 every month" — record a template that repeats, not a single row. */
    CREATE_RECURRING,

    /** "make that $6 instead" — change an earlier transaction. Not implemented yet. */
    UPDATE_TRANSACTION,

    /** "remove the 2,500 restaurant expense" — retire a transaction the user already recorded. */
    DELETE_TRANSACTION,

    /**
     * "cancel my Netflix subscription" — declined on purpose. Recognised so it can be
     * refused with an answer that helps, rather than misread as a capture and written.
     */
    DELETE_RECURRING,

    /** "how much did I spend on food?" — a read-side question (F-1.16). */
    QUERY,

    /** Nothing usable was read, or the model was unavailable. */
    UNKNOWN
}
