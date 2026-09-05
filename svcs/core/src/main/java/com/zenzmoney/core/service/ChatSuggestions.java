package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.IntentType;
import com.zenzmoney.common.domain.TransactionType;
import com.zenzmoney.common.i18n.MessageKey;
import com.zenzmoney.common.i18n.Msg;
import com.zenzmoney.core.entity.ParsedIntent;
import com.zenzmoney.core.web.dto.ChatPromptView;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Decides the one thing to ask about a half-read draft, and the sentence to ask it
 * with (F-1.11).
 *
 * <p><b>One question at a time, most blocking first.</b> A draft can be short of
 * three things at once, and asking for all three is a form, not a conversation. The
 * order is amount, then direction, then category, then cadence — the category list
 * depends on whether money went out or came in, so it cannot be asked first, and the
 * cadence only ever applies to a template.
 *
 * <p>The class exists to keep the sentence and the field in one place: the reply the
 * user reads and the slot their answer fills are decided together, so they cannot
 * drift apart.
 */
@Service
public class ChatSuggestions {

    /** The intents that produce a draft worth asking about. */
    private static final Set<IntentType> CAPTURE =
            EnumSet.of(IntentType.CREATE_TRANSACTION, IntentType.CREATE_RECURRING);

    /**
     * The question to put to the user, or null when the draft asks for nothing — it is
     * complete, it is not a capture, or what it lacks is not something the user can
     * answer in chat (an unset currency needs onboarding, not a sentence).
     */
    public ChatPromptView promptFor(ParsedIntent draft) {
        if (draft == null || !CAPTURE.contains(draft.getIntent()) || draft.isComplete()) {
            return null;
        }
        List<String> missing = draft.getMissingFields();
        if (missing.contains("currency")) {
            return null;
        }
        if (missing.contains("amount")) {
            return amountPrompt(draft);
        }
        if (missing.contains("type")) {
            return prompt("type", Msg.CHAT_ASK_TYPE);
        }
        if (missing.contains("category")) {
            return categoryPrompt(draft.getTxnType());
        }
        if (missing.contains("cadence")) {
            return prompt("cadence", Msg.CHAT_ASK_CADENCE);
        }
        return null;
    }

    /**
     * Acknowledges the direction before asking, because "Sure, I can add that expense"
     * tells the user their message was understood — a bare "How much was that?" leaves
     * them guessing whether to start again.
     */
    private ChatPromptView amountPrompt(ParsedIntent draft) {
        if (draft.getTxnType() == null) {
            return prompt("amount", Msg.CHAT_ASK_AMOUNT);
        }
        return prompt("amount", draft.getTxnType() == TransactionType.INCOME
                ? Msg.CHAT_ASK_AMOUNT_INCOME
                : Msg.CHAT_ASK_AMOUNT_EXPENSE);
    }

    private ChatPromptView categoryPrompt(TransactionType type) {
        return prompt("category", type == TransactionType.INCOME
                ? Msg.CHAT_ASK_CATEGORY_INCOME
                : Msg.CHAT_ASK_CATEGORY_EXPENSE);
    }

    /** The view carries the key; the boundary renders it (see {@code ChatText}). */
    private static ChatPromptView prompt(String field, MessageKey question) {
        return new ChatPromptView(field, question.key());
    }
}
