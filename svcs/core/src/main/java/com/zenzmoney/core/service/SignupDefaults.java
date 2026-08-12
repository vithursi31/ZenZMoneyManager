package com.zenzmoney.core.service;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Currency;
import java.util.Locale;

/**
 * Provisional preferences derived from what the client reports about itself at
 * signup (F-1.27), so a user who never opens the onboarding screen still lands in
 * a sane currency and language.
 *
 * <p>Deliberately lenient, and that leniency is the whole difference between this
 * and the parsing in {@link OnboardingService}. There the user picked the value and
 * a bad one is worth refusing; here it is an unverified hint from the client, and a
 * malformed one must never cost somebody their registration. Everything
 * unrecognised degrades to "no opinion" and leaves onboarding to ask properly.
 */
public final class SignupDefaults {

    /** The default until the user says otherwise; onboarding also offers ta and si (F-1.26). */
    public static final String LANGUAGE = "en";

    private SignupDefaults() {
    }

    /**
     * ISO-4217 for the locale's country — {@code en-GB} → {@code GBP}, {@code si-LK}
     * → {@code LKR} — or null when the client sent nothing, sent a language with no
     * region ({@code en} alone), or named something that is not an ISO-3166 country
     * with a currency of its own.
     *
     * <p><b>There is no fallback currency by design.</b> Guessing one for a user we
     * know nothing about would denominate their whole ledger in it, and marking the
     * guess unconfirmed advertises that it happened without making a wrong one cheap.
     * No signal means no currency, exactly as before this seeding existed.
     */
    public static String currencyFor(String localeTag) {
        if (localeTag == null || localeTag.isBlank()) {
            return null;
        }
        // Clients send both en-GB and en_US; only the hyphenated form is a language tag.
        Locale locale = Locale.forLanguageTag(localeTag.trim().replace('_', '-'));
        if (locale.getCountry().isEmpty()) {
            return null;
        }
        try {
            Currency currency = Currency.getInstance(locale);
            return currency == null ? null : currency.getCurrencyCode();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The reported IANA zone, normalised, or null if it is not one. It decides where
     * the user's month boundaries fall (§1.10), so an unrecognised value falls back
     * to the column default rather than being stored.
     */
    public static String timezoneFor(String zoneId) {
        if (zoneId == null || zoneId.isBlank()) {
            return null;
        }
        try {
            return ZoneId.of(zoneId.trim()).getId();
        } catch (DateTimeException e) {
            return null;
        }
    }
}
