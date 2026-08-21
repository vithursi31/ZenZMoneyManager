package com.zenzmoney.core.config;

import com.zenzmoney.core.i18n.SupportedLocaleResolver;
import com.zenzmoney.core.util.SupportedLanguages;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

@Configuration
public class LocaleConfig {

    /**
     * The bundles behind every user-facing message. Three settings here are load-bearing:
     *
     * <ul>
     *   <li>UTF-8 — without it Sinhala and Tamil come out as mojibake.
     *   <li>{@code fallbackToSystemLocale=false} — otherwise an unmatched locale falls to the
     *       <em>JVM</em> default, which is English on every developer machine and whatever the
     *       host says in production. It must fall to the base bundle instead.
     *   <li>{@code useCodeAsDefaultMessage=false} — a missing key must degrade to the English
     *       default the caller supplies, never to {@code error.category.duplicate} in a user's face.
     * </ul>
     */
    @Bean
    public MessageSource messageSource() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasename("classpath:i18n/messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        source.setUseCodeAsDefaultMessage(false);
        return source;
    }

    /**
     * Resolves {@code Accept-Language} against the allowlist, so an attacker-supplied header can
     * only ever select one of ours. Also what DispatcherServlet seeds LocaleContextHolder from —
     * which is why it has to be the same matcher the rest of the app uses, not a second opinion.
     */
    @Bean
    public AcceptHeaderLocaleResolver localeResolver(SupportedLanguages supportedLanguages) {
        return new SupportedLocaleResolver(supportedLanguages);
    }
}
