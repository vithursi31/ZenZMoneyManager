package com.zenzmoney.core.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

public final class DisposableDomainValidator {

    private static final String RESOURCE = "data/emails/disposable_domains.txt";
    private static final Set<String> DISPOSABLE_DOMAINS = load();

    private DisposableDomainValidator() {}

    public static boolean isDisposableDomain(String email) {
        if (email == null) return false;
        int at = email.lastIndexOf('@');
        if (at < 0) return false;
        String domain = email.substring(at + 1).trim().toLowerCase();
        return DISPOSABLE_DOMAINS.contains(domain);
    }

    private static Set<String> load() {
        Set<String> domains = new HashSet<>();
        try (InputStream in = DisposableDomainValidator.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("Missing classpath resource: " + RESOURCE);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String domain = line.trim().toLowerCase();
                    if (!domain.isEmpty()) domains.add(domain);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + RESOURCE, e);
        }
        return domains;
    }
}
