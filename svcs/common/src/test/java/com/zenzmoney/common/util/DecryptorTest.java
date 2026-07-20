package com.zenzmoney.common.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecryptorTest {

    private Decryptor decryptor;

    @BeforeEach
    void setUp() {
        decryptor = new Decryptor();
    }

    @Test
    void encryptProducesEncPrefixedBase64() {
        String encrypted = decryptor.encrypt("hello world");

        assertNotNull(encrypted);
        assertTrue(encrypted.startsWith("ENC:"), "expected ENC: prefix");

        Base64.getDecoder().decode(encrypted.substring(4));
    }

    @Test
    void encryptThenDecryptRoundTripsAscii() {
        String plain = "the quick brown fox jumps over the lazy dog";
        assertEquals(plain, decryptor.decrypt(decryptor.encrypt(plain)));
    }

    @Test
    void encryptThenDecryptRoundTripsUtf8() {
        String plain = "héllo — 你好 — こんにちは — 🌍";
        assertEquals(plain, decryptor.decrypt(decryptor.encrypt(plain)));
    }

    @Test
    void encryptThenDecryptRoundTripsEmpty() {
        assertEquals("", decryptor.decrypt(decryptor.encrypt("")));
    }

    @Test
    void encryptUsesFreshIvSoOutputIsNotDeterministic() {
        String plain = "same input";
        String a = decryptor.encrypt(plain);
        String b = decryptor.encrypt(plain);

        assertNotEquals(a, b, "two encryptions of the same plaintext must differ");
        assertEquals(plain, decryptor.decrypt(a));
        assertEquals(plain, decryptor.decrypt(b));
    }

    @Test
    void decryptPassesThroughUnprefixedInput() {
        String raw = "plain-config-value";
        assertEquals(raw, decryptor.decrypt(raw));
    }

    @Test
    void decryptOfTamperedCiphertextThrows() {
        String encrypted = decryptor.encrypt("sensitive");
        byte[] bytes = Base64.getDecoder().decode(encrypted.substring(4));
        // Flip a bit in the last ciphertext byte — corrupts the final block so
        // PKCS5 padding validation fails on decrypt.
        bytes[bytes.length - 1] ^= 0x01;
        String tampered = "ENC:" + Base64.getEncoder().encodeToString(bytes);

        assertThrows(RuntimeException.class, () -> decryptor.decrypt(tampered));
    }
}
