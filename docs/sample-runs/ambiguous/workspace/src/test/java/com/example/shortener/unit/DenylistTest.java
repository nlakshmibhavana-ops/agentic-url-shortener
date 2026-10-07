package com.example.shortener.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.shortener.domain.Errors;
import com.example.shortener.service.UrlValidator;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Q-SAFETY: a configurable domain denylist that also covers subdomains. */
class DenylistTest {

    private static final List<String> BLOCKED = List.of("evil.example", "phish.test");

    private static String check(String url) {
        return UrlValidator.validate(url, 2048, "http://sho.rt", false, BLOCKED);
    }

    @Tag("AC-domain_denylist-1")
    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example/x", "https://login.evil.example/", "http://PHISH.test"})
    void blocksListedDomainsAndTheirSubdomains(String url) {
        assertThatThrownBy(() -> check(url)).isInstanceOf(Errors.InvalidUrl.class).hasMessageContaining("blocked");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://notevil.example/", "https://evil.example.com/"})
    void allowsLookAlikeButDifferentDomains(String url) {
        assertThat(check(url)).isNotBlank();
    }
}
