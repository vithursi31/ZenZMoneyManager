package com.zenzmoney.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisposableDomainValidatorTest {

    @Test
    void isDisposableDomain_flagsKnownDisposableProvider() {
        assertTrue(DisposableDomainValidator.isDisposableDomain("someone@mailinator.com"));
    }

    @Test
    void isDisposableDomain_isCaseInsensitiveOnTheDomain() {
        assertTrue(DisposableDomainValidator.isDisposableDomain("someone@MAILINATOR.COM"));
    }

    @Test
    void isDisposableDomain_allowsAnOrdinaryProvider() {
        assertFalse(DisposableDomainValidator.isDisposableDomain("someone@gmail.com"));
    }

    @Test
    void isDisposableDomain_rejectsNothingForMalformedInput() {
        assertFalse(DisposableDomainValidator.isDisposableDomain("not-an-email"));
        assertFalse(DisposableDomainValidator.isDisposableDomain(null));
    }
}
