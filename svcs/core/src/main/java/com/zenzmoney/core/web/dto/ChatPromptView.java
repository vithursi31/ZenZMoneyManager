package com.zenzmoney.core.web.dto;

import lombok.Getter;

import java.util.function.UnaryOperator;

/**
 * The one thing the assistant is asking for. Null on a turn that asks for nothing —
 * a written entry, a refusal, or a failed reading.
 *
 * <p>Derived from the draft rather than stored: {@code missingFields} already says
 * what is open, so a replayed conversation rebuilds its question without a column to
 * keep in step.
 *
 * <p><b>No tappable options.</b> An earlier design offered the answers as numbered
 * chips; typing the answer turned out to be both simpler to build and simpler to use,
 * and it is the only path a voice client (F-1.12) could ever take. What survives is
 * the part that mattered: one field, asked one at a time, with the sentence and the
 * field decided in the same place so they cannot disagree.
 */
@Getter
public class ChatPromptView {

    /** Which slot the answer fills: {@code amount}, {@code type}, {@code category}, {@code cadence}. */
    private final String field;

    /** The same sentence the assistant replied with, so a client can render either one. */
    private final String question;

    public ChatPromptView(String field, String question) {
        this.field = field;
        this.question = question;
    }

    /** A copy with {@code question} rendered from its message key — boundary code only. */
    ChatPromptView localized(UnaryOperator<String> text) {
        return new ChatPromptView(field, text.apply(question));
    }
}
