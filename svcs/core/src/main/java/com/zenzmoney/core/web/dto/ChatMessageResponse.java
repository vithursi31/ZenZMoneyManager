package com.zenzmoney.core.web.dto;

import com.zenzmoney.common.domain.ChatMessageStatus;
import com.zenzmoney.common.domain.ChatRole;
import com.zenzmoney.core.entity.ChatMessage;
import lombok.Getter;

import java.util.function.UnaryOperator;

/**
 * One turn of a conversation, for replaying history.
 *
 * <p>An assistant turn's {@code content} is a message key until the boundary renders
 * it, so a user who changes language sees their own history in the new one. A user
 * turn, and an answer the model wrote (F-1.16), are stored as text and pass through.
 */
@Getter
public class ChatMessageResponse {

    private final String id;
    private final ChatRole role;
    private final String content;
    private final ChatMessageStatus status;
    private final String transactionId;
    private final String recurringId;
    private final Long createdTime;
    private final ParsedIntentView draft;
    /** Set only on the turn still awaiting an answer — an earlier question is spent. */
    private final ChatPromptView prompt;

    private ChatMessageResponse(String id, ChatRole role, String content, ChatMessageStatus status,
                                String transactionId, String recurringId, Long createdTime,
                                ParsedIntentView draft, ChatPromptView prompt) {
        this.id = id;
        this.role = role;
        this.content = content;
        this.status = status;
        this.transactionId = transactionId;
        this.recurringId = recurringId;
        this.createdTime = createdTime;
        this.draft = draft;
        this.prompt = prompt;
    }

    private ChatMessageResponse(ChatMessage message, ChatPromptView prompt) {
        this(message.getId(), message.getRole(), message.getContent(), message.getStatus(),
                message.getTransactionId(), message.getRecurringId(), message.getCreatedTime(),
                ParsedIntentView.of(message.getParsedIntent()), prompt);
    }

    public static ChatMessageResponse of(ChatMessage message) {
        return new ChatMessageResponse(message, null);
    }

    public static ChatMessageResponse of(ChatMessage message, ChatPromptView prompt) {
        return new ChatMessageResponse(message, prompt);
    }

    /** A copy with every message key rendered in the caller's language — boundary code only. */
    public ChatMessageResponse localized(UnaryOperator<String> text) {
        return new ChatMessageResponse(id, role, text.apply(content), status, transactionId,
                recurringId, createdTime, draft, prompt == null ? null : prompt.localized(text));
    }
}
