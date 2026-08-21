package com.zenzmoney.core.i18n;

import com.zenzmoney.core.util.SupportedLanguages;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.Enumeration;
import java.util.Locale;

/**
 * Resolves {@code Accept-Language} through {@link SupportedLanguages}, so the language + script rule
 * has exactly one implementation.
 *
 * <p>The stock {@code AcceptHeaderLocaleResolver} matches its {@code supportedLocales} list itself,
 * and its idea of a match is not ours: given {@code zh-CN} and {@code zh-TW} to choose from it
 * answers {@code zh-HK} and {@code zh-Hant} with the default. Running it first and post-processing
 * its answer does not help — by then the script subtag is gone and every Traditional-reading variant
 * has already collapsed to English. So the list-matching is replaced rather than wrapped.
 *
 * <p>What is kept from the superclass is the part worth keeping: the servlet container has already
 * parsed the header into q-value-ordered locales, so {@link HttpServletRequest#getLocales()} is the
 * caller's real preference order and the first one we can serve wins.
 */
public class SupportedLocaleResolver extends AcceptHeaderLocaleResolver {

    private final SupportedLanguages supportedLanguages;

    public SupportedLocaleResolver(SupportedLanguages supportedLanguages) {
        this.supportedLanguages = supportedLanguages;
        setDefaultLocale(SupportedLanguages.DEFAULT);
        setSupportedLocales(supportedLanguages.all());
    }

    @Override
    public Locale resolveLocale(HttpServletRequest request) {
        // No header at all: getLocales() would hand back the container's own default, which is the
        // server's language and has nothing to do with this caller.
        if (request.getHeader("Accept-Language") == null) {
            return SupportedLanguages.DEFAULT;
        }
        Enumeration<Locale> requested = request.getLocales();
        while (requested.hasMoreElements()) {
            Locale match = supportedLanguages.match(requested.nextElement().toLanguageTag());
            if (match != null) {
                return match;
            }
        }
        return SupportedLanguages.DEFAULT;
    }
}
