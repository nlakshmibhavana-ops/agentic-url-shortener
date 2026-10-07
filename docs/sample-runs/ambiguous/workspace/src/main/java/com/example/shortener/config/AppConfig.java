package com.example.shortener.config;

import com.example.shortener.service.TokenBucketRateLimiter;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    TokenBucketRateLimiter rateLimiter(ShortenerProperties props, Clock clock) {
        return new TokenBucketRateLimiter(props.rateLimitPerMinute(), props.rateLimitPerMinute(), () -> {
            Instant now = clock.instant();
            return now.getEpochSecond() * 1_000_000_000L + now.getNano();
        }, 10_000);
    }

    /** Clicks are recorded off the request thread so analytics never slows a redirect. */
    @Bean
    Executor clickExecutor(ShortenerProperties props) {
        return props.asyncClicks() ? Executors.newVirtualThreadPerTaskExecutor() : Runnable::run;
    }
}
