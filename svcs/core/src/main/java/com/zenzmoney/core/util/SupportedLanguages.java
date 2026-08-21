package com.zenzmoney.core.util;

import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.common.i18n.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The languages the server can answer in — {@code zenzmoney.i18n.available-languages}, one message
 * bundle under {@code i18n/} per entry. Add a language by dropping in
 * {@code messages_<tag>.properties} and adding its tag to that property; nothing here changes.
 *
 * <p><b>The config names bundles exactly; matching a caller's tag is what is lenient.</b> Two
 * different jobs, deliberately split:
 *
 * <ul>
 *   <li><b>Config</b> — {@code en,zh-CN,zh-TW,fr,…}. Each entry must have a bundle file, checked at
 *       startup. A tag with no bundle is dropped, loudly.
 *   <li><b>Matching</b> — a caller's tag is matched on <em>language plus script</em>. Region is
 *       ignored, so {@code fr-CA} gets {@code fr} and {@code pt-BR} gets {@code pt}. Script is not,
 *       because for Chinese it changes the text: {@code zh-TW}, {@code zh-HK} and {@code zh-Hant}
 *       all resolve to Traditional, {@code zh-CN}, {@code zh-SG}, {@code zh-Hans} and a bare
 *       {@code zh} to Simplified.
 * </ul>
 */
@Component
public class SupportedLanguages {

    private static final Logger log = LoggerFactory.getLogger(SupportedLanguages.class);

    /**
     * English is the floor, not a choice: {@code messages.properties} — the bundle with no suffix —
     * <em>is</em> the English text, and every other language falls back to it key by key. Changing
     * this would mean renaming the base bundle, so it is not configurable.
     */
    public static final Locale DEFAULT = Locale.ENGLISH;

    private final List<Locale> supported;

    public SupportedLanguages(
            @Value("${zenzmoney.i18n.available-languages:en}") String availableLanguages) {
        this.supported = parse(availableLanguages);
        log.info("Serving user-facing messages in {}", tags());
    }

    public List<Locale> all() {
        return supported;
    }

    /** The supported locale a language tag maps to, or null — never a guess and never the default. */
    public Locale match(String languageTag) {
        Locale requested = parseTag(languageTag);
        if (requested == null) {
            return null;
        }
        for (Locale candidate : supported) {
            if (sameLanguageAndScript(candidate, requested)) {
                return candidate;
            }
        }
        return null;
    }

    /** The locale to use for a stored preference, falling back to {@link #DEFAULT}. */
    public Locale resolveOrDefault(String languageTag) {
        Locale matched = match(languageTag);
        return matched != null ? matched : DEFAULT;
    }

    /** The canonical stored form of a language tag, or null when it is not one we can serve. */
    public String normalise(String languageTag) {
        Locale matched = match(languageTag);
        return matched == null ? null : matched.toLanguageTag();
    }

    /**
     * The canonical stored form, or a rejection. Storing a language with no bundle behind it is a
     * lie the user only discovers by every message staying English, so it is refused on write.
     */
    public String normaliseOrThrow(String languageTag) {
        String normalised = normalise(languageTag);
        if (normalised == null) {
            throw new BadRequestException(Msg.LANGUAGE_UNSUPPORTED, languageTag);
        }
        return normalised;
    }

    public List<String> tags() {
        return supported.stream().map(Locale::toLanguageTag).toList();
    }

    /** Where a locale's bundle lives, and the one place that spelling is decided. */
    public static String bundleResource(Locale locale) {
        if (DEFAULT.equals(locale)) {
            return "i18n/messages.properties";
        }
        return "i18n/messages_" + locale.toLanguageTag().replace('-', '_') + ".properties";
    }

    /**
     * The locale to take display names from — the script, not the region, because the script is what
     * the user is actually choosing between. {@code zh-CN} names itself "Chinese (China)" but
     * {@code zh-Hans} names itself "Chinese (Simplified)", which is both what the two bundles
     * actually differ by and what a picker should say. Everything else names itself.
     */
    public static Locale namingLocale(Locale locale) {
        String script = scriptOf(locale);
        if (script.isEmpty()) {
            return locale;
        }
        return new Locale.Builder().setLanguage(locale.getLanguage()).setScript(script).build();
    }

    private static boolean sameLanguageAndScript(Locale a, Locale b) {
        return a.getLanguage().equals(b.getLanguage()) && scriptOf(a).equals(scriptOf(b));
    }

    /**
     * The language we have more than one bundle for. Everything else is matched on language alone,
     * so a script or region subtag on it is simply ignored — {@code en-Latn-US} is still English.
     *
     * <p>If a second multi-script language ever ships (Serbian Cyrillic/Latin is the usual next one)
     * this becomes a set, and {@link #scriptOf} learns its region map. One entry is not worth a
     * lookup table yet.
     */
    private static final String SCRIPT_SENSITIVE_LANGUAGE = "zh";

    /**
     * The writing system a locale implies, or {@code ""} when the language has only one bundle.
     *
     * <p>Chinese is the only language here where the tag changes the <em>text</em> rather than just
     * the spelling of a date. Both halves of the gate matter: without it {@code en-SG} would come
     * out Simplified because Singapore is in the region map, and {@code en-Latn-US} would fail to
     * match plain {@code en} because it carries a script subtag at all.
     */
    private static String scriptOf(Locale locale) {
        if (!SCRIPT_SENSITIVE_LANGUAGE.equals(locale.getLanguage())) {
            return "";
        }
        if (!locale.getScript().isEmpty()) {
            return locale.getScript();
        }
        return switch (locale.getCountry()) {
            case "TW", "HK", "MO" -> "Hant";
            default -> "Hans";   // CN, SG, MY, and a bare "zh"
        };
    }

    /** Clients send both zh-TW and zh_TW; only the hyphenated form is a language tag. */
    private static Locale parseTag(String languageTag) {
        if (languageTag == null || languageTag.isBlank()) {
            return null;
        }
        Locale locale = Locale.forLanguageTag(languageTag.trim().replace('_', '-'));
        return locale.getLanguage().isEmpty() ? null : locale;
    }

    /**
     * A configured tag with no bundle is dropped rather than served, and logged loudly. This used to
     * be impossible — the list lived in a file packaged next to the bundles, so the completeness test
     * caught it at build time. As Spring config it can now be overridden per profile or by an env
     * var on a running server, where the only symptom would be a language a user can select that
     * silently answers in English.
     */
    private static List<Locale> parse(String configured) {
        List<Locale> locales = new ArrayList<>();
        locales.add(DEFAULT);
        for (String tag : configured.split(",")) {
            Locale locale = parseTag(tag);
            if (locale == null) {
                continue;
            }
            // Two entries that match the same callers would make the second unreachable.
            if (locales.stream().anyMatch(l -> sameLanguageAndScript(l, locale))) {
                continue;
            }
            if (!hasBundle(locale)) {
                log.error("Language '{}' is configured in zenzmoney.i18n.available-languages but has "
                        + "no {} on the classpath — dropping it, because offering it would answer "
                        + "in English with no explanation", locale.toLanguageTag(), bundleResource(locale));
                continue;
            }
            locales.add(locale);
        }
        return Collections.unmodifiableList(locales);
    }

    private static boolean hasBundle(Locale locale) {
        return SupportedLanguages.class.getClassLoader().getResource(bundleResource(locale)) != null;
    }
}
