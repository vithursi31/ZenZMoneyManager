package com.zenzmoney.core.i18n;

import com.zenzmoney.common.i18n.MessageKey;
import com.zenzmoney.common.i18n.Msg;
import com.zenzmoney.common.status.ServiceCodes;
import com.zenzmoney.common.status.StatusCode;
import com.zenzmoney.common.status.StatusCodes;
import com.zenzmoney.core.util.SupportedLanguages;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard that stops the key registry and the bundles drifting apart.
 *
 * <p>Without it the failure is silent and late: a rejection whose key nobody added to the bundle
 * quietly answers with its error code's generic text instead, which reads like the wrong message
 * rather than a missing one, and only in the language nobody on the team is reading.
 */
class MessageBundleCompletenessTest {

    private static final String BASE = "i18n/messages.properties";

    /** Whatever the application ships with — see SupportedLanguagesTest for the parsing itself. */
    private static final List<Locale> LANGUAGES = new SupportedLanguages("en,zh-CN,zh-TW,fr,es,pt,de,it,ru,ja,ko,si").all();

    @Test
    void everyMessageKeyHasAnEntryInTheBaseBundle() throws Exception {
        Properties base = load(BASE);
        List<String> missing = new ArrayList<>();
        for (MessageKey key : constantsOf(Msg.class, MessageKey.class)) {
            if (!base.containsKey(key.key())) {
                missing.add(key.key());
            }
        }
        assertTrue(missing.isEmpty(), "Msg constants with no entry in " + BASE + ": " + missing);
    }

    /** Every code is a fallback message in its own right — see MessageResolver. */
    @Test
    void everyStatusCodeHasAGenericEntryInTheBaseBundle() throws Exception {
        Properties base = load(BASE);
        List<String> missing = new ArrayList<>();
        for (StatusCode code : constantsOf(ServiceCodes.class, StatusCode.class)) {
            if (!base.containsKey("error.code." + code.code())) {
                missing.add(code.code());
            }
        }
        for (StatusCode code : constantsOf(StatusCodes.class, StatusCode.class)) {
            if (!base.containsKey("error.code." + code.code())) {
                missing.add(code.code());
            }
        }
        assertTrue(missing.isEmpty(), "status codes with no error.code.<CODE> entry: " + missing);
    }

    /** A supported language with no bundle would silently serve English to everyone who picks it. */
    @Test
    void everySupportedLanguageHasABundle() throws Exception {
        for (Locale locale : LANGUAGES) {
            if (locale.equals(SupportedLanguages.DEFAULT)) {
                continue;
            }
            assertNotNull(resource(bundleFor(locale)),
                    "SupportedLanguages lists " + locale + " but there is no " + bundleFor(locale));
        }
    }

    /** A key here that the base does not carry is a typo: nothing will ever read it. */
    @Test
    void noTranslationCarriesAKeyTheBaseBundleDoesNot() throws Exception {
        Set<Object> baseKeys = load(BASE).keySet();
        for (Locale locale : LANGUAGES) {
            if (locale.equals(SupportedLanguages.DEFAULT)) {
                continue;
            }
            Set<String> extra = new TreeSet<>();
            for (Object key : load(bundleFor(locale)).keySet()) {
                if (!baseKeys.contains(key)) {
                    extra.add(key.toString());
                }
            }
            assertTrue(extra.isEmpty(), bundleFor(locale) + " has keys the base bundle does not: " + extra);
        }
    }

    /**
     * A MessageFormat pattern eats single quotes, so {@code named '{0}'} renders the placeholder
     * literally. Every entry with a placeholder is parsed here, in every language, and the ones
     * that should show quotes are checked for them.
     */
    @Test
    void everyPatternParses_andQuotedPlaceholdersSurvive() throws Exception {
        for (Locale locale : LANGUAGES) {
            String bundle = locale.equals(SupportedLanguages.DEFAULT) ? BASE : bundleFor(locale);
            Properties props = load(bundle);
            for (String key : props.stringPropertyNames()) {
                String pattern = props.getProperty(key);
                if (!pattern.contains("{0}")) {
                    continue;
                }
                String rendered = new MessageFormat(pattern, locale).format(new Object[] {"X", 1, 2});
                assertTrue(rendered.contains("X"),
                        bundle + " / " + key + ": {0} did not interpolate — check the apostrophes in \""
                                + pattern + "\"");
            }
        }
    }

    /**
     * The one entry whose quoting is load-bearing: the category name is meant to come out visibly
     * quoted, and a MessageFormat-eaten quote is exactly what would silently remove the quotes.
     *
     * <p>Deliberately not a count of ASCII {@code '} — most languages quote with their own marks
     * (Chinese “…”, French « … », German „…“, Japanese 「…」), and an ASCII-only assertion would
     * fail correct translations while passing a broken one that happened to use straight quotes.
     * What is actually checked is that <em>some</em> quotation mark survives on both sides.
     */
    @Test
    void categoryDuplicate_keepsItsQuotes() throws Exception {
        for (Locale locale : LANGUAGES) {
            String bundle = locale.equals(SupportedLanguages.DEFAULT) ? BASE : bundleFor(locale);
            String pattern = load(bundle).getProperty(Msg.CATEGORY_DUPLICATE.key());
            String rendered = new MessageFormat(pattern, locale).format(new Object[] {"Groceries"});

            int start = rendered.indexOf("Groceries");
            assertTrue(start >= 0, bundle + ": the name did not interpolate at all — " + rendered);
            assertTrue(isQuote(nearestNonSpaceBefore(rendered, start)),
                    bundle + ": no opening quote around the name — " + rendered);
            assertTrue(isQuote(nearestNonSpaceAfter(rendered, start + "Groceries".length())),
                    bundle + ": no closing quote around the name — " + rendered);
        }
    }

    /** Straight, typographic, guillemet and CJK corner brackets — whatever the language quotes with. */
    private static boolean isQuote(char c) {
        return "'\"\u2018\u2019\u201C\u201D\u201E\u00AB\u00BB\u300C\u300D".indexOf(c) >= 0;
    }

    private static char nearestNonSpaceBefore(String text, int index) {
        for (int i = index - 1; i >= 0; i--) {
            if (text.charAt(i) != ' ') return text.charAt(i);
        }
        return '\0';
    }

    private static char nearestNonSpaceAfter(String text, int index) {
        for (int i = index; i < text.length(); i++) {
            if (text.charAt(i) != ' ') return text.charAt(i);
        }
        return '\0';
    }

    private static String bundleFor(Locale locale) {
        return SupportedLanguages.bundleResource(locale);
    }

    private static InputStream resource(String path) {
        return MessageBundleCompletenessTest.class.getClassLoader().getResourceAsStream(path);
    }

    private static Properties load(String path) throws IOException {
        try (InputStream in = resource(path)) {
            assertNotNull(in, "missing classpath resource: " + path);
            Properties props = new Properties();
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return props;
        }
    }

    private static <T> List<T> constantsOf(Class<?> registry, Class<T> type) throws IllegalAccessException {
        List<T> values = new ArrayList<>();
        for (Field field : registry.getDeclaredFields()) {
            if (type.isAssignableFrom(field.getType())) {
                values.add(type.cast(field.get(null)));
            }
        }
        return values;
    }
}
