package com.zenzmoney.common.status;

import com.zenzmoney.common.i18n.Msg;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the registry itself. Two constants sharing a code is the failure mode this catches: it
 * compiles, it reads fine, and it silently makes one of them undiagnosable on the wire.
 */
class ServiceCodesTest {

    private static Map<String, StatusCode> codes() throws Exception {
        Map<String, StatusCode> byName = new HashMap<>();
        // getFields() includes StatusCodes' constants, inherited through ServiceCodes.
        for (Field f : ServiceCodes.class.getFields()) {
            if (f.getType() == StatusCode.class) {
                byName.put(f.getName(), (StatusCode) f.get(null));
            }
        }
        return byName;
    }

    @Test
    void everyCodeIsUniqueAcrossTheRegistry() throws Exception {
        Map<String, String> owner = new HashMap<>();
        for (Map.Entry<String, StatusCode> e : codes().entrySet()) {
            String clash = owner.put(e.getValue().code(), e.getKey());
            assertNull(clash, e.getValue().code() + " is used by both " + clash + " and " + e.getKey());
        }
    }

    @Test
    void everyCodeIsWellFormed() throws Exception {
        for (Map.Entry<String, StatusCode> e : codes().entrySet()) {
            StatusCode sc = e.getValue();
            assertTrue(sc.code().matches("E1\\d{3}"),
                    e.getKey() + " must be an E1nnn code, was " + sc.code());
            assertFalse(sc.description().isBlank(), e.getKey() + " has no default message");
            assertTrue(sc.httpStatus() >= 400 && sc.httpStatus() <= 599,
                    e.getKey() + " must answer with a 4xx/5xx, was " + sc.httpStatus());
        }
    }

    @Test
    void theRegistryIsNotEmpty() throws Exception {
        assertTrue(codes().size() > 10, "the registry should hold every code the API can answer with");
    }

    /** {@code with()} is a message override, not a new code — equality stays on the code. */
    @Test
    void withKeepsTheCodeAndStatus() {
        StatusCode overridden = ServiceCodes.SC_NOT_FOUND.with("no transaction with id 9f3c");

        assertEquals(ServiceCodes.SC_NOT_FOUND.code(), overridden.code());
        assertEquals(ServiceCodes.SC_NOT_FOUND.httpStatus(), overridden.httpStatus());
        assertEquals(ServiceCodes.SC_NOT_FOUND, overridden);
    }

    /**
     * {@code with(String)} is a <em>diagnostic</em>: it lands on {@code detail()} for the log and
     * leaves {@code description()} — the registry's own English default — untouched, so nothing a
     * call site scribbles can end up in a response body. The user-facing text comes from a key.
     */
    @Test
    void aStringOverrideIsADiagnostic_andDoesNotBecomeTheDescription() {
        StatusCode overridden = ServiceCodes.SC_NOT_FOUND.with("no transaction with id 9f3c");

        assertEquals("no transaction with id 9f3c", overridden.detail());
        assertEquals(ServiceCodes.SC_NOT_FOUND.description(), overridden.description());
        assertNull(ServiceCodes.SC_NOT_FOUND.detail(), "the registry constant itself carries none");
    }

    /** A key override carries the key and its arguments, and still is not a new code. */
    @Test
    void aKeyOverrideCarriesTheKeyAndItsArguments() {
        StatusCode overridden = ServiceCodes.SC_BAD_REQUEST.with(Msg.CATEGORY_DUPLICATE, "Groceries");

        assertEquals(Msg.CATEGORY_DUPLICATE, overridden.messageKey());
        assertArrayEquals(new Object[] {"Groceries"}, overridden.args());
        assertEquals(ServiceCodes.SC_BAD_REQUEST, overridden);
        assertNull(overridden.detail());
    }

    /** The two are independent: a rejection can be readable to the user and detailed in the log. */
    @Test
    void aKeyAndADiagnosticCoexist() {
        StatusCode both = ServiceCodes.SC_BAD_REQUEST
                .with(Msg.CATEGORY_DUPLICATE, "Groceries")
                .with("uq_category_name_per_kind violated");

        assertEquals(Msg.CATEGORY_DUPLICATE, both.messageKey());
        assertEquals("uq_category_name_per_kind violated", both.detail());
        assertEquals("uq_category_name_per_kind violated", both.logMessage());
    }

    /** With no diagnostic, the log falls back to the registry's English default. */
    @Test
    void logMessageFallsBackToTheDescription() {
        assertEquals(ServiceCodes.SC_NOT_FOUND.description(), ServiceCodes.SC_NOT_FOUND.logMessage());
    }
}
