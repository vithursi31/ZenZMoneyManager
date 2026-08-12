package com.zenzmoney.core.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * These hints arrive from an unauthenticated client on the registration path, so
 * the property under test throughout is that nothing here can <em>fail</em> — a
 * malformed locale costs the user a default, never their signup.
 */
class SignupDefaultsTest {

    @Test
    void currency_comesFromTheLocalesCountry() {
        assertEquals("LKR", SignupDefaults.currencyFor("si-LK"));
        assertEquals("GBP", SignupDefaults.currencyFor("en-GB"));
        assertEquals("JPY", SignupDefaults.currencyFor("ja-JP"));
    }

    /** Android and older clients send the underscore form; it means the same thing. */
    @Test
    void currency_acceptsTheUnderscoreForm() {
        assertEquals("USD", SignupDefaults.currencyFor("en_US"));
        assertEquals("LKR", SignupDefaults.currencyFor(" ta_LK "));
    }

    /**
     * A language alone says nothing about where someone banks — en is spoken in
     * dozens of currencies — so it yields no guess rather than a coin flip.
     */
    @Test
    void currency_isNullWithoutARegion() {
        assertNull(SignupDefaults.currencyFor("en"));
        assertNull(SignupDefaults.currencyFor("ta"));
    }

    @Test
    void currency_isNullWhenNothingUsableWasSent() {
        assertNull(SignupDefaults.currencyFor(null));
        assertNull(SignupDefaults.currencyFor(""));
        assertNull(SignupDefaults.currencyFor("   "));
        assertNull(SignupDefaults.currencyFor("not a locale at all"));
        assertNull(SignupDefaults.currencyFor("en-ZZ"));   // ZZ is not an ISO-3166 country
    }

    /** A real country that issues no currency of its own — the null-return branch. */
    @Test
    void currency_isNullForACountryWithoutOne() {
        assertNull(SignupDefaults.currencyFor("en-AQ"));
    }

    @Test
    void timezone_isNormalisedWhenReal() {
        assertEquals("Asia/Colombo", SignupDefaults.timezoneFor("Asia/Colombo"));
        assertEquals("Asia/Colombo", SignupDefaults.timezoneFor("  Asia/Colombo  "));
        assertEquals("UTC", SignupDefaults.timezoneFor("UTC"));
    }

    /** It decides where the user's months begin (§1.10), so a bad one is not stored. */
    @Test
    void timezone_isNullWhenNotAZone() {
        assertNull(SignupDefaults.timezoneFor(null));
        assertNull(SignupDefaults.timezoneFor(""));
        assertNull(SignupDefaults.timezoneFor("Mars/Olympus"));
    }

    @Test
    void language_defaultsToEnglish() {
        assertEquals("en", SignupDefaults.LANGUAGE);
    }
}
