package com.zenzmoney.core.web.dto;

import com.zenzmoney.common.domain.ChatMessageStatus;
import com.zenzmoney.core.entity.ChatMessage;
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

    private ChatReplyResponse(ChatMessage assistantTurn) {
        this.messageId = assistantTurn.getId();
        this.sessionId = assistantTurn.getSessionId();
        this.status = assistantTurn.getStatus();
        this.reply = assistantTurn.getContent();
        this.draft = ParsedIntentView.of(assistantTurn.getParsedIntent());
    }

    public static ChatReplyResponse of(ChatMessage assistantTurn) {
        return new ChatReplyResponse(assistantTurn);
    }
}
