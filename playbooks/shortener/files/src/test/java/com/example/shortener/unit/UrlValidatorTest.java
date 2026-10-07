package com.example.shortener.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.shortener.domain.Errors;
import com.example.shortener.service.UrlValidator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UrlValidatorTest {

    private static String check(String url) {
        return UrlValidator.validate(url, 2048, "http://sho.rt", false);
    }

    @Test
    void normalisesSchemeAndHostButKeepsPathCase() {
        assertThat(check("HTTPS://Example.COM/Path?q=A#Frag")).isEqualTo("https://example.com/Path?q=A#Frag");
    }

    @Tag("AC-url_safety-1")
    @Tag("AC-url_safety-2")
    @ParameterizedTest
    @ValueSource(strings = {
        "javascript:alert(1)", "data:text/html,<script>alert(1)</script>", "ftp://example.com/file",
        "file:///etc/passwd", "https://", "https://user:pass@example.com/", "https://paypal.com@evil.example/",
        "http://localhost:8080/admin", "http://127.0.0.1/", "http://10.0.0.5/", "http://192.168.1.1/",
        "http://169.254.169.254/latest/meta-data/", "http://[::1]/", "http://db.internal/", "http://sho.rt/abc",
        "https://example.com/has space", "https://example.com/\nheader", ""})
    void rejectsUnsafeUrls(String url) {
        assertThatThrownBy(() -> check(url)).isInstanceOf(Errors.InvalidUrl.class);
    }

    @Test
    void rejectsOverlongUrls() {
        assertThatThrownBy(() -> UrlValidator.validate("https://e.com/" + "a".repeat(50), 40, "http://sho.rt", false))
                .isInstanceOf(Errors.InvalidUrl.class);
    }

    @Test
    void privateHostsAllowedWhenConfigured() {
        assertThat(UrlValidator.validate("http://10.0.0.5/x", 2048, "http://sho.rt", true))
                .isEqualTo("http://10.0.0.5/x");
    }

    @Test
    void keepsExplicitPortAndIpv6() {
        assertThat(check("https://example.com:8443/a")).isEqualTo("https://example.com:8443/a");
        assertThat(check("http://[2606:4700::1111]/")).isEqualTo("http://[2606:4700::1111]/");
    }
}
