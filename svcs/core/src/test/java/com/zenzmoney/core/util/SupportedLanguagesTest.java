package com.zenzmoney.core.util;

import com.zenzmoney.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The allowlist is configuration ({@code zenzmoney.i18n.available-languages}), so these cover both
 * halves: what a given setting parses to, and the lenient region matching a client depends on — the
 * stored preference and the {@code Accept-Language} header disagree about region in practice, and a
 * client sending {@code si-LK} must still get Sinhala.
 */
class SupportedLanguagesTest {

    private static final Locale SINHALA = Locale.forLanguageTag("si");

    private static final String SHIPPED = "en,zh-CN,zh-TW,fr,es,pt,de,it,ru,ja,ko,si";

    private final SupportedLanguages languages = new SupportedLanguages("en,si");
    private final SupportedLanguages chinese = new SupportedLanguages("en,zh-CN,zh-TW");

    // ── parsing the configured value ────────────────────────────────────────────

    @Test
    void theConfiguredTagsBecomeTheAllowlist() {
        assertEquals(List.of("en", "si"), languages.tags());
    }

    /**
     * A language nobody translated must not be offerable. This is the case the old build-time file
     * made impossible; as Spring config it can be set on a running server, so it is checked here.
     */
    @Test
    void aConfiguredLanguageWithNoBundleIsDropped() {
        // Dutch: deliberately a language the app ships no messages_nl.properties for.
        assertEquals(List.of("en", "si"), new SupportedLanguages("en,si,nl").tags());
        assertNull(new SupportedLanguages("en,si,nl").match("nl"));
    }

    @Test
    void englishSurvivesEvenIfTheConfigOmitsIt() {
        assertEquals(List.of("en", "si"), new SupportedLanguages("si").tags());
        assertEquals(List.of("en"), new SupportedLanguages("").tags());
    }

    /**
     * The config names bundles, so a tag is kept as written — {@code si-LK} would mean
     * {@code messages_si_LK.properties}, which does not exist, so it is dropped and the bare
     * {@code si} is what survives. Leniency about region belongs in matching, not here.
     */
    @Test
    void configuredTagsNameBundlesExactly_andAreDeduplicated() {
        assertEquals(List.of("en", "si"), new SupportedLanguages(" en , si-LK ,si_LK, si").tags());
    }

    /** Two entries that would match the same callers make the second unreachable, so it is dropped. */
    @Test
    void aDuplicateOfAnAlreadyReachableLocaleIsDropped() {
        assertEquals(List.of("en", "zh-CN"), new SupportedLanguages("en,zh-CN,zh-SG").tags());
    }

    @Test
    void junkInTheConfigIsSkipped_notFatal() {
        assertEquals(List.of("en", "si"), new SupportedLanguages("en,,!!!,si, ").tags());
    }

    // ── matching a caller's tag ─────────────────────────────────────────────────

    @Test
    void exactTagMatches() {
        assertEquals(Locale.ENGLISH, languages.match("en"));
    }

    @Test
    void regionIsIgnoredWhenTheLanguageIsSupported() {
        assertEquals(SINHALA, languages.match("si-LK"));
        assertEquals(Locale.ENGLISH, languages.match("en-GB"));
    }

    /** Clients send both forms; only the hyphenated one is a language tag. */
    @Test
    void underscoreFormIsAccepted() {
        assertEquals(SINHALA, languages.match("si_LK"));
    }

    @Test
    void unsupportedOrJunkIsNotAGuess() {
        assertNull(languages.match("fr"));
        assertNull(languages.match("zz-ZZ"));
        assertNull(languages.match("  "));
        assertNull(languages.match(null));
        assertNull(languages.match("!!!"));
    }

    @Test
    void normaliseReturnsTheCanonicalTag() {
        assertEquals("si", languages.normalise("si-LK"));
        assertEquals("en", languages.normalise("EN-us"));
        assertNull(languages.normalise("fr-FR"));
    }

    /**
     * What gets stored on {@code app_user.language} is the tag of the <em>bundle</em> that matched,
     * never what the client sent — so every English variant collapses to a single {@code en} and the
     * column never accumulates {@code en-US}, {@code en-GB} and {@code en-UK} as if they were three
     * different settings. {@code en-UK} is not even a real tag (the region is {@code GB}); it still
     * normalises, because the region is not what we match on.
     */
    @Test
    void regionOnlyVariantsAllStoreAsTheBareLanguage() {
        SupportedLanguages all = new SupportedLanguages(SHIPPED);
        for (String tag : List.of("en-US", "en-GB", "en-UK", "en_US", "EN-us", "en-Latn-US")) {
            assertEquals("en", all.normalise(tag), tag + " must store as plain en");
        }
        assertEquals("pt", all.normalise("pt-BR"));
        assertEquals("fr", all.normalise("fr-CA"));
        assertEquals("si", all.normalise("si-LK"));
    }

    /**
     * The one exception, and the reason the rule is "the bundle's tag" rather than "the language":
     * Chinese has two bundles, so the region has to survive into the stored value.
     */
    @Test
    void chineseKeepsItsRegion_becauseItNamesADifferentBundle() {
        SupportedLanguages all = new SupportedLanguages(SHIPPED);
        assertEquals("zh-TW", all.normalise("zh-HK"));
        assertEquals("zh-TW", all.normalise("zh-Hant"));
        assertEquals("zh-CN", all.normalise("zh-SG"));
        assertEquals("zh-CN", all.normalise("zh"));
    }

    @Test
    void resolveOrDefaultNeverReturnsNull() {
        assertEquals(Locale.ENGLISH, languages.resolveOrDefault("fr"));
        assertEquals(Locale.ENGLISH, languages.resolveOrDefault(null));
        assertEquals(SINHALA, languages.resolveOrDefault("si"));
    }

    @Test
    void normaliseOrThrowRefusesWhatItCannotServe() {
        assertEquals("si", languages.normaliseOrThrow("si-LK"));
        assertThrows(BadRequestException.class, () -> languages.normaliseOrThrow("fr"));
        assertThrows(BadRequestException.class, () -> languages.normaliseOrThrow("nonsense"));
    }

    // ── the value the application actually ships with ───────────────────────────

    /**
     * The default in {@code application.properties} must name only languages that are in the jar,
     * otherwise a fresh deployment offers one that answers in English.
     */
    @Test
    void theShippedDefaultOnlyNamesLanguagesWithBundles() throws Exception {
        String configured = shippedSetting();
        assertFalse(configured.isBlank(), "zenzmoney.i18n.available-languages is missing");

        SupportedLanguages shipped = new SupportedLanguages(configured);
        for (String tag : configured.split(",")) {
            Locale locale = Locale.forLanguageTag(tag.trim());
            assertTrue(shipped.tags().contains(locale.toLanguageTag()),
                    "application.properties offers '" + locale.toLanguageTag() + "' but it was dropped "
                            + "for want of " + SupportedLanguages.bundleResource(locale));
        }
    }

    /** The shipped set must cover both Chinese scripts, or half the readership silently gets the other. */
    @Test
    void theShippedDefaultCoversBothChineseScripts() throws Exception {
        SupportedLanguages shipped = new SupportedLanguages(shippedSetting());

        assertEquals(Locale.forLanguageTag("zh-CN"), shipped.match("zh-CN"));
        assertEquals(Locale.forLanguageTag("zh-TW"), shipped.match("zh-TW"));
    }

    private static String shippedSetting() throws Exception {
        try (InputStream in = SupportedLanguagesTest.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            assertNotNull(in, "missing application.properties");
            Properties props = new Properties();
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            // The shipped line is ${AVAILABLE_LANGUAGES:en,si}; the default is what a plain run uses.
            String raw = props.getProperty("zenzmoney.i18n.available-languages", "");
            return raw.startsWith("${") ? raw.substring(raw.indexOf(':') + 1, raw.length() - 1) : raw;
        }
    }

    // ── Chinese: the one language where region changes the text, not just the spelling ──────

    @Test
    void bothChineseScriptsAreOfferedSeparately() {
        assertEquals(List.of("en", "zh-CN", "zh-TW"), chinese.tags());
    }

    @Test
    void traditionalRegionsResolveToTaiwan() {
        for (String tag : List.of("zh-TW", "zh-HK", "zh-MO", "zh-Hant", "zh-Hant-TW", "zh_TW")) {
            assertEquals(Locale.forLanguageTag("zh-TW"), chinese.match(tag),
                    tag + " is written in Traditional characters");
        }
    }

    @Test
    void simplifiedRegionsResolveToTheMainland() {
        for (String tag : List.of("zh-CN", "zh-SG", "zh-Hans", "zh-Hans-CN", "zh_CN")) {
            assertEquals(Locale.forLanguageTag("zh-CN"), chinese.match(tag),
                    tag + " is written in Simplified characters");
        }
    }

    /** A caller who says only "Chinese" gets Simplified — the larger readership. */
    @Test
    void bareChineseResolvesToSimplified() {
        assertEquals(Locale.forLanguageTag("zh-CN"), chinese.match("zh"));
        assertEquals("zh-CN", chinese.normalise("zh"));
    }

    /**
     * The region map is gated on Chinese. Singapore is in it, and {@code en-SG} must not come out
     * as Simplified Chinese because of that.
     */
    @Test
    void theChineseRegionMapDoesNotLeakIntoOtherLanguages() {
        SupportedLanguages all = new SupportedLanguages(SHIPPED);
        assertEquals(Locale.ENGLISH, all.match("en-SG"));
        assertEquals(Locale.ENGLISH, all.match("en-HK"));
        assertEquals(Locale.forLanguageTag("fr"), all.match("fr-CA"));
        assertEquals(Locale.forLanguageTag("pt"), all.match("pt-BR"));
    }

    /** A script subtag on a language with one bundle is noise, not a reason to stop matching. */
    @Test
    void aScriptSubtagOnASingleBundleLanguageIsIgnored() {
        SupportedLanguages all = new SupportedLanguages(SHIPPED);
        assertEquals(Locale.ENGLISH, all.match("en-Latn"));
        assertEquals(Locale.ENGLISH, all.match("en-Latn-US"));
        assertEquals(Locale.forLanguageTag("ru"), all.match("ru-Cyrl-RU"));
        assertEquals(Locale.forLanguageTag("ja"), all.match("ja-Jpan-JP"));
    }

    /** Offering only Simplified must not silently serve it to a Traditional reader. */
    @Test
    void aTraditionalRequestIsUnmatchedWhenOnlySimplifiedIsOffered() {
        SupportedLanguages simplifiedOnly = new SupportedLanguages("en,zh-CN");
        assertNull(simplifiedOnly.match("zh-TW"));
        assertEquals(Locale.ENGLISH, simplifiedOnly.resolveOrDefault("zh-TW"));
    }

    @Test
    void bundleResourceSpellsTheFilename() {
        assertEquals("i18n/messages.properties", SupportedLanguages.bundleResource(Locale.ENGLISH));
        assertEquals("i18n/messages_zh_TW.properties",
                SupportedLanguages.bundleResource(Locale.forLanguageTag("zh-TW")));
        assertEquals("i18n/messages_fr.properties",
                SupportedLanguages.bundleResource(Locale.forLanguageTag("fr")));
    }
}
