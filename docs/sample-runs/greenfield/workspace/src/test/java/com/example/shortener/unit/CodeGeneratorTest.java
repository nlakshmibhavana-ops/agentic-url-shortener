package com.example.shortener.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.shortener.domain.Errors;
import com.example.shortener.service.CodeGenerator;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CodeGeneratorTest {

    private final CodeGenerator codes = new CodeGenerator();

    @Test
    void generatesBase62CodesOfTheRequestedLength() {
        assertThat(codes.generate(9)).hasSize(9).matches("[0-9A-Za-z]{9}");
    }

    @Test
    void codesAreNotSequential() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            seen.add(codes.generate(7));
        }
        assertThat(seen).hasSize(500);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "spring-sale", "Q4_promo", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void acceptsValidAliases(String alias) {
        assertThat(codes.validateAlias(alias)).isEqualTo(alias);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "has space", "emoji🙂", "../etc", "API",
        "healthz"})
    void rejectsInvalidOrReservedAliases(String alias) {
        assertThatThrownBy(() -> codes.validateAlias(alias)).isInstanceOf(Errors.InvalidAlias.class);
    }
}
