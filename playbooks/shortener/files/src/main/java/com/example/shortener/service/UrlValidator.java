package com.example.shortener.service;

import com.example.shortener.domain.Errors;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Target-URL validation. The service never fetches targets; these rules stop abuse of the redirect
 * itself (script URLs, credential spoofing, internal hosts, redirect loops).
 */
public final class UrlValidator {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private UrlValidator() {
    }

    public static String validate(String url, int maxLength, String baseUrl, boolean allowPrivate) {
        if (url == null || url.isBlank()) {
            throw new Errors.InvalidUrl("url is required");
        }
        url = url.strip();
        if (url.length() > maxLength) {
            throw new Errors.InvalidUrl("url exceeds " + maxLength + " characters");
        }
        if (url.chars().anyMatch(c -> Character.isWhitespace(c) || c < 0x20 || c == 0x7f)) {
            throw new Errors.InvalidUrl("url contains whitespace or control characters");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new Errors.InvalidUrl("url is not well-formed");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            throw new Errors.InvalidUrl("only http and https URLs can be shortened");
        }
        if (uri.getRawUserInfo() != null) {
            throw new Errors.InvalidUrl("URLs with embedded credentials are not allowed");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty()) {
            throw new Errors.InvalidUrl("url must include a host");
        }
        try {
            IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES);
        } catch (IllegalArgumentException e) {
            if (!host.startsWith("[")) {
                throw new Errors.InvalidUrl("url host is not a valid domain name");
            }
        }
        if (!allowPrivate && isPrivateHost(host)) {
            throw new Errors.InvalidUrl("private, loopback and internal hosts are not allowed");
        }
        String own = URI.create(baseUrl).getHost();
        if (own != null && host.equals(own.toLowerCase(Locale.ROOT))) {
            throw new Errors.InvalidUrl("cannot shorten a link to this service");
        }
        try {
            return new URI(scheme, uri.getRawAuthority().toLowerCase(Locale.ROOT), null, null, null)
                    + (uri.getRawPath() == null ? "" : uri.getRawPath())
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())
                    + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment());
        } catch (URISyntaxException e) {
            throw new Errors.InvalidUrl("url is not well-formed");
        }
    }

    static boolean isPrivateHost(String host) {
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")
                || host.endsWith(".internal")) {
            return true;
        }
        boolean literal = host.startsWith("[") || IPV4.matcher(host).matches();
        if (!literal) {
            return false;
        }
        try {
            // A literal address: parsed locally, no DNS lookup happens.
            InetAddress ip = InetAddress.getByName(host.replace("[", "").replace("]", ""));
            return ip.isLoopbackAddress() || ip.isSiteLocalAddress() || ip.isLinkLocalAddress()
                    || ip.isAnyLocalAddress() || ip.isMulticastAddress()
                    || (ip.getAddress().length == 16 && (ip.getAddress()[0] & 0xfe) == 0xfc);
        } catch (java.net.UnknownHostException e) {
            return true;
        }
    }
}
