package com.zenzmoney.core.web.dto;

import lombok.Getter;

import java.util.List;

/**
 * The one thing the assistant is asking for, and the answers it offers. Null on a
 * turn that asks for nothing — a complete draft, a refusal, or a failed reading.
 *
 * <p>Derived from the draft rather than stored: {@code missingFields} already says
 * what is open, so a replayed conversation rebuilds its chips without a column to
 * keep in step.
 */
@Getter
public class ChatPromptView {

    /** Which slot the answer fills: {@code amount}, {@code type}, or {@code category}. */
    private final String field;

    /** The same sentence the assistant replied with, so a client can render either one. */
    private final String question;

    private final List<ChatOptionView> options;

    public ChatPromptView(String field, String question, List<ChatOptionView> options) {
        this.field = field;
        this.question = question;
        this.options = List.copyOf(options);
    }
}
