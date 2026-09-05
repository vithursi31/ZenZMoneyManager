package com.zenzmoney.core.web.dto;

import com.zenzmoney.common.domain.ChatMessageStatus;
import com.zenzmoney.core.entity.ChatMessage;
import com.zenzmoney.core.service.insight.SpendingSnapshot;
import lombok.Getter;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * The assistant's answer to one capture message.
 *
 * <p>{@code results} is the whole answer: one entry per money event the message
 * named. The scalar fields beside it describe the <b>last</b> result and exist so a
 * client written against the single-entry shape keeps working — a message naming one
 * amount, which is nearly all of them, reads identically either way.
 *
 * <p>Every sentence here is a message key until the boundary renders it, and none of
 * them carries formatted money or a formatted date: the draft holds minor units, a
 * currency and epoch millis, and the client formats them (§0.1/§0.2).
 */
@Getter
public class ChatReplyResponse {

    private final String messageId;
    private final String sessionId;
    private final ChatMessageStatus status;
    private final String reply;
    private final ParsedIntentView draft;
    /** What is still missing. Null when nothing is asked. */
    private final ChatPromptView prompt;

    /** One per money event read from the message; never empty on a capture. */
    private final List<ChatResultView> results;

    /**
     * The figures behind an answered question (F-1.16) — null on every other turn.
     * Returned beside the prose on purpose: the same aggregates the model was given,
     * so a client can draw the breakdown and a reader can check the sentence against
     * it rather than taking a language model's word about their own money.
     */
    private final SpendingSnapshot insight;

    private ChatReplyResponse(String messageId, String sessionId, ChatMessageStatus status,
                              String reply, ParsedIntentView draft, ChatPromptView prompt,
                              List<ChatResultView> results, SpendingSnapshot insight) {
        this.messageId = messageId;
        this.sessionId = sessionId;
        this.status = status;
        this.reply = reply;
        this.draft = draft;
        this.prompt = prompt;
        this.results = List.copyOf(results);
        this.insight = insight;
    }

    private ChatReplyResponse(ChatMessage turn, ChatPromptView prompt,
                              List<ChatResultView> results, SpendingSnapshot insight) {
        this(turn.getId(), turn.getSessionId(), turn.getStatus(), turn.getContent(),
                ParsedIntentView.of(turn.getParsedIntent()), prompt, results, insight);
    }

    /** A single-result reply — the ordinary case, and every amendment. */
    public static ChatReplyResponse of(ChatMessage turn, ChatPromptView prompt) {
        return new ChatReplyResponse(turn, prompt, List.of(ChatResultView.of(turn, prompt)), null);
    }

    /**
     * A reply covering several results. {@code last} is the turn the scalar fields
     * describe — the open one when the message left something to ask about, otherwise
     * the final entry written.
     */
    public static ChatReplyResponse of(ChatMessage last, ChatPromptView lastPrompt,
                                       List<ChatResultView> results) {
        return new ChatReplyResponse(last, lastPrompt, results, null);
    }

    /** An answered question: no draft, no results, figures instead. */
    public static ChatReplyResponse answered(ChatMessage turn, SpendingSnapshot insight) {
        return new ChatReplyResponse(turn, null, List.of(), insight);
    }

    /** A copy with every message key rendered in the caller's language — boundary code only. */
    public ChatReplyResponse localized(UnaryOperator<String> text) {
        return new ChatReplyResponse(messageId, sessionId, status, text.apply(reply), draft,
                prompt == null ? null : prompt.localized(text),
                results.stream().map(r -> r.localized(text)).toList(), insight);
    }
}
