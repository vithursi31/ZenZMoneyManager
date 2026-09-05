package com.zenzmoney.core.web.dto;

import com.zenzmoney.common.domain.ChatMessageStatus;
import com.zenzmoney.core.entity.ChatMessage;
import lombok.Getter;

import java.util.function.UnaryOperator;

/**
 * One outcome from one message. A message naming three amounts produces three of
 * these — the sample conversation's three bubbles — each its own turn with its own
 * status and its own way back.
 *
 * <p>{@code reply} carries no formatted money and no formatted date: the draft holds
 * minor units, a currency and epoch millis, and the client renders the card from
 * those (§0.1/§0.2). A backend that writes "$48.86" into a sentence has quietly taken
 * on locale and currency formatting for every future client.
 */
@Getter
public class ChatResultView {

    private final String messageId;
    private final ChatMessageStatus status;
    private final String reply;
    private final ParsedIntentView draft;

    /** The ledger row this turn wrote, when it wrote one. Null otherwise. */
    private final String transactionId;

    /** The template this turn wrote, when the message described something that repeats. */
    private final String recurringId;

    /** What is still missing. Set on at most one result per message — see ChatService. */
    private final ChatPromptView prompt;

    private ChatResultView(String messageId, ChatMessageStatus status, String reply,
                           ParsedIntentView draft, String transactionId, String recurringId,
                           ChatPromptView prompt) {
        this.messageId = messageId;
        this.status = status;
        this.reply = reply;
        this.draft = draft;
        this.transactionId = transactionId;
        this.recurringId = recurringId;
        this.prompt = prompt;
    }

    public static ChatResultView of(ChatMessage turn, ChatPromptView prompt) {
        return new ChatResultView(turn.getId(), turn.getStatus(), turn.getContent(),
                ParsedIntentView.of(turn.getParsedIntent()), turn.getTransactionId(),
                turn.getRecurringId(), prompt);
    }

    /** A copy with the message keys rendered — boundary code only. */
    ChatResultView localized(UnaryOperator<String> text) {
        return new ChatResultView(messageId, status, text.apply(reply), draft,
                transactionId, recurringId, prompt == null ? null : prompt.localized(text));
    }
}
