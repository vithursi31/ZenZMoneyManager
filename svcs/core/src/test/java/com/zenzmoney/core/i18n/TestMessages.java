package com.zenzmoney.core.i18n;

import com.zenzmoney.core.config.LocaleConfig;
import org.mockito.Mockito;

import java.util.Locale;

/**
 * Message plumbing for unit tests, built from the <em>real</em> {@link LocaleConfig} bean so a test
 * cannot pass against settings the application does not use.
 */
public final class TestMessages {

    private TestMessages() {}

    public static MessageResolver resolver() {
        return new MessageResolver(new LocaleConfig().messageSource());
    }

    public static RequestLocale fixedLocale(Locale locale) {
        RequestLocale requestLocale = Mockito.mock(RequestLocale.class);
        Mockito.when(requestLocale.resolve()).thenReturn(locale);
        return requestLocale;
    }
}
