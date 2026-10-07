package com.example.shortener.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class TestClockConfig {

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock();
    }
}
