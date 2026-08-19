package com.zenzmoney.core.web.dto;

import com.zenzmoney.common.domain.ChatMessageStatus;
import com.zenzmoney.core.entity.ChatMessage;
import com.zenzmoney.core.service.insight.SpendingSnapshot;
import lombok.Getter;

/**
 * The assistant's answer to one capture message.
 *
 * <p>{@code reply} is deliberately free of formatted money and dates: the draft
 * carries minor units, a currency, and epoch millis, and the client formats them
 * for display (§0.1/§0.2). A backend that renders "$5.00" into a sentence has
 * quietly taken on locale and currency formatting for every future client.
 */
@Getter
public class ChatReplyResponse {

    private final String messageId;
    private final String sessionId;
    private final ChatMessageStatus status;
    private final String reply;
    private final ParsedIntentView draft;
    /** What is still missing and the answers offered for it. Null when nothing is asked. */
    private final ChatPromptView prompt;

    /**
     * The figures behind an answered question (F-1.16) — null on every other turn.
     * Returned beside the prose on purpose: the same aggregates the model was given,
     * so a client can draw the breakdown and a reader can check the sentence against
     * it rather than taking a language model's word about their own money.
     */
    private final SpendingSnapshot insight;

    private ChatReplyResponse(ChatMessage assistantTurn, ChatPromptView prompt, SpendingSnapshot insight) {
        this.messageId = assistantTurn.getId();
        this.sessionId = assistantTurn.getSessionId();
        this.status = assistantTurn.getStatus();
        this.reply = assistantTurn.getContent();
        this.draft = ParsedIntentView.of(assistantTurn.getParsedIntent());
        this.prompt = prompt;
        this.insight = insight;
    }

    public static ChatReplyResponse of(ChatMessage assistantTurn, ChatPromptView prompt) {
        return new ChatReplyResponse(assistantTurn, prompt, null);
    }

    /** An answered question: no draft, no chips, figures instead. */
    public static ChatReplyResponse answered(ChatMessage assistantTurn, SpendingSnapshot insight) {
        return new ChatReplyResponse(assistantTurn, null, insight);
    }
}
