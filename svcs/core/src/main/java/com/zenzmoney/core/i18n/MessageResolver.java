package com.zenzmoney.core.i18n;

import com.zenzmoney.common.i18n.MessageKey;
import com.zenzmoney.common.status.StatusCode;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Turns a {@link StatusCode} into the sentence a caller reads, in their language.
 *
 * <p>Three levels, each strictly safer than the next: the call site's own message key, then the
 * generic message for the error code itself ({@code error.code.E1013}), then the English default
 * compiled into the registry. A rejection that has not been given a key yet therefore answers with
 * its code's generic text — never with a raw key, and never with a call-site diagnostic.
 */
@Service
public class MessageResolver {

    private static final String CODE_PREFIX = "error.code.";

    private final MessageSource messageSource;

    public MessageResolver(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    public String render(StatusCode statusCode, Locale locale) {
        String generic = messageSource.getMessage(
                CODE_PREFIX + statusCode.code(), null, statusCode.description(), locale);
        MessageKey key = statusCode.messageKey();
        if (key == null) {
            return generic;
        }
        return messageSource.getMessage(key.key(), statusCode.args(), generic, locale);
    }

    /** For text that is not an error — emails today, chat copy next. */
    public String render(MessageKey key, Locale locale, Object... args) {
        return messageSource.getMessage(key.key(), args, key.key(), locale);
    }

    /**
     * A bean-validation field error. Spring's {@code FieldError} resolves through codes such as
     * {@code Size.updateProfileRequest.firstName} down to a bare {@code Size}, and carries the
     * validator's own English text as its default — so an unmapped constraint still reads sensibly.
     */
    public String render(MessageSourceResolvable resolvable, Locale locale) {
        return messageSource.getMessage(resolvable, locale);
    }
}
