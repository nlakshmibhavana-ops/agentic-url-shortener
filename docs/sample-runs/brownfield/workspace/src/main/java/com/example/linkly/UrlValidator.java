package com.example.linkly;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * Target-URL validation (LINK-142). Only absolute http(s) URLs with a host are accepted. Script-capable
 * schemes such as javascript: and data: turn a redirect into stored XSS, so they are rejected here, before
 * anything is written to the database.
 */
public final class UrlValidator {

    static final int MAX_URL_LENGTH = 2048;
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private UrlValidator() {
    }

    public static class InvalidUrlException extends Exception {
        InvalidUrlException(String message) {
            super(message);
        }
    }

    public static String validate(String url) throws InvalidUrlException {
        String value = url == null ? "" : url.strip();
        if (value.isEmpty() || value.length() > MAX_URL_LENGTH) {
            throw new InvalidUrlException("url must be 1-" + MAX_URL_LENGTH + " characters");
        }
        if (value.chars().anyMatch(c -> Character.isWhitespace(c) || c < 0x20)) {
            throw new InvalidUrlException("url contains whitespace or control characters");
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw new InvalidUrlException("url is not well-formed");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            throw new InvalidUrlException("only http and https URLs are allowed");
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new InvalidUrlException("url must include a host");
        }
        if (uri.getRawUserInfo() != null) {
            throw new InvalidUrlException("URLs with embedded credentials are not allowed");
        }
        return value;
    }
}
