package com.zenzmoney.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupportedCurrenciesTest {

    @Test
    void contains_flagsAnActiveCurrency() {
        assertTrue(SupportedCurrencies.contains("USD"));
        assertTrue(SupportedCurrencies.contains("LKR"));
    }

    @Test
    void contains_excludesARetiredCurrency() {
        // RUR: the pre-1998 Russian Ruble. The JDK's own Currency class still
        // recognizes the code, but no country has used it in decades.
        assertFalse(SupportedCurrencies.contains("RUR"));
    }

    @Test
    void contains_rejectsGarbage() {
        assertFalse(SupportedCurrencies.contains("XYZ"));
        assertFalse(SupportedCurrencies.contains(""));
    }
}
