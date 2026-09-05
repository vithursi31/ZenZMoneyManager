package com.zenzmoney.core.i18n;

import com.zenzmoney.common.i18n.MessageKey;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * Renders the assistant's own sentences in the caller's language (F-1.26).
 *
 * <p>Chat is the one place a <em>successful</em> response carries text the app wrote,
 * so it needs the same key-then-render split the error path has — and for the same
 * reason. {@code ChatService} stores and returns keys; this turns them into sentences
 * at the boundary, which keeps a {@code Locale} out of the service exactly as
 * {@link com.zenzmoney.core.web.advice.GlobalExceptionHandler} does for exceptions.
 *
 * <p><b>Not every stored line is a key.</b> A user's own message, and an answer the
 * model wrote (F-1.16), are text and pass through untouched — as do the English
 * sentences written into {@code chat_message.content} before this existed. The
 * {@code chat.} prefix is what tells them apart, which is why every reply key carries
 * it and no other key does.
 */
@Component
public class ChatText {

    /** The prefix that marks stored content as a key rather than a sentence. */
    private static final String CHAT_PREFIX = "chat.";

    private final MessageResolver messages;
    private final RequestLocale requestLocale;

    public ChatText(MessageResolver messages, RequestLocale requestLocale) {
        this.messages = messages;
        this.requestLocale = requestLocale;
    }

    /** A renderer bound to this caller's language, for one response. */
    public UnaryOperator<String> forCaller() {
        return forLocale(requestLocale.resolve());
    }

    /**
     * A renderer bound to a fixed language. English is what the extraction prompt is
     * fed, so a pending question reaches the model in the language it reasons in — the
     * same rule the logs follow.
     */
    public UnaryOperator<String> forLocale(Locale locale) {
        return content -> render(content, locale);
    }

    private String render(String content, Locale locale) {
        if (content == null || !content.startsWith(CHAT_PREFIX)) {
            return content;
        }
        return messages.render(MessageKey.of(content), locale);
    }
}
