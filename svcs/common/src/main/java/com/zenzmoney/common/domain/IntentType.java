package com.zenzmoney.common.domain;

/**
 * What the user's chat message is asking for, as read by the extraction model
 * (chat entry plan §5.1). Only {@link #CREATE_TRANSACTION} is acted on today;
 * the rest are recognised so the pipeline can decline them explicitly instead of
 * mis-reading them as a capture.
 */
public enum IntentType {

    /** "I spent $5 for burger" — record a new income or expense. */
    CREATE_TRANSACTION,

    /** "make that $6 instead" — change an earlier transaction. Not implemented yet. */
    UPDATE_TRANSACTION,

    /** "how much did I spend on food?" — a read-side question (F-1.10b). */
    QUERY,

    /** Nothing usable was read, or the model was unavailable. */
    UNKNOWN
}
