package com.example.shortener.service;

import com.example.shortener.domain.Errors;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Random base62 short codes and custom-alias rules. */
@Component
public class CodeGenerator {

    static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final Pattern ALIAS = Pattern.compile("[A-Za-z0-9_-]{3,32}");
    /** Paths the service owns; an alias must never shadow them. */
    static final Set<String> RESERVED = Set.of("api", "healthz", "readyz", "v3", "swagger-ui", "admin",
            "index.html", "favicon.ico");

    private final SecureRandom random = new SecureRandom();

    /** Random rather than sequential, so the code space cannot be enumerated. */
    public String generate(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    public String validateAlias(String alias) {
        if (alias == null || !ALIAS.matcher(alias).matches()) {
            throw new Errors.InvalidAlias("alias must be 3-32 characters of letters, digits, '-' or '_'");
        }
        if (RESERVED.contains(alias.toLowerCase(Locale.ROOT))) {
            throw new Errors.InvalidAlias("alias '" + alias + "' is reserved");
        }
        return alias;
    }
}
