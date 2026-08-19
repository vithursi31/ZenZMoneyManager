package com.zenzmoney.core.web.dto;

import com.zenzmoney.common.domain.ChatMessageStatus;
import com.zenzmoney.common.domain.ChatRole;
import com.zenzmoney.core.entity.ChatMessage;
import lombok.Getter;

/** One turn of a conversation, for replaying history. */
@Getter
public class ChatMessageResponse {

    private final String id;
    private final ChatRole role;
    private final String content;
    private final ChatMessageStatus status;
    private final String transactionId;
    private final Long createdTime;
    private final ParsedIntentView draft;
    /** Set only on the turn still awaiting an answer — an earlier question is spent. */
    private final ChatPromptView prompt;

    private ChatMessageResponse(ChatMessage message, ChatPromptView prompt) {
        this.id = message.getId();
        this.role = message.getRole();
        this.content = message.getContent();
        this.status = message.getStatus();
        this.transactionId = message.getTransactionId();
        this.createdTime = message.getCreatedTime();
        this.draft = ParsedIntentView.of(message.getParsedIntent());
        this.prompt = prompt;
    }

    public static ChatMessageResponse of(ChatMessage message) {
        return new ChatMessageResponse(message, null);
    }

    public static ChatMessageResponse of(ChatMessage message, ChatPromptView prompt) {
        return new ChatMessageResponse(message, prompt);
    }
}
