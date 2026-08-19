package com.zenzmoney.core.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * ISO-4217 codes the app offers as an account currency — a maintained allowlist
 * (data/currencies/supported_currencies.txt), not everything the JDK's Currency
 * registry happens to still know about (which includes retired codes, e.g. the
 * pre-1998 Russian Ruble, RUR). Add or remove a line in that file to change what
 * a user can pick; nothing here needs to change.
 */
public final class SupportedCurrencies {

    private static final String RESOURCE = "data/currencies/supported_currencies.txt";
    private static final Set<String> CODES = load();

    private SupportedCurrencies() {}

    public static boolean contains(String currencyCode) {
        return CODES.contains(currencyCode);
    }

    public static Set<String> codes() {
        return CODES;
    }

    private static Set<String> load() {
        Set<String> codes = new HashSet<>();
        try (InputStream in = SupportedCurrencies.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("Missing classpath resource: " + RESOURCE);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String code = line.trim();
                    if (code.isEmpty() || code.startsWith("#")) continue;
                    codes.add(code.toUpperCase(Locale.ROOT));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + RESOURCE, e);
        }
        return Collections.unmodifiableSet(codes);
    }
}
