package com.zenzmoney.common.status;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

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
        StatusCode overridden = ServiceCodes.SC_NOT_FOUND.with("Transaction not found");

        assertEquals(ServiceCodes.SC_NOT_FOUND.code(), overridden.code());
        assertEquals(ServiceCodes.SC_NOT_FOUND.httpStatus(), overridden.httpStatus());
        assertEquals("Transaction not found", overridden.description());
        assertEquals(ServiceCodes.SC_NOT_FOUND, overridden);
    }
}
