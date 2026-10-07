package com.example.shortener.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Full application on a random port, in-memory database, controllable clock, synchronous clicks. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:shortener;DB_CLOSE_DELAY=-1",
    "shortener.base-url=http://sho.rt",
    "shortener.api-keys=test-key-1,test-key-2,test-key-limits",
    "shortener.rate-limit-per-minute=5",
    "shortener.async-clicks=false",
    "shortener.blocked-domains=evil.example,phish.test"
})
@Import(TestClockConfig.class)
public @interface IntegrationTest {
}
