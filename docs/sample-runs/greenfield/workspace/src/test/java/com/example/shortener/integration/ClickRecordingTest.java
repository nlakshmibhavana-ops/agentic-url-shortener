package com.example.shortener.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.shortener.domain.Link;
import com.example.shortener.service.LinkService;
import com.example.shortener.support.Http;
import com.example.shortener.support.IntegrationTest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

@IntegrationTest
@Import(ClickRecordingTest.HeldClicks.class)
class ClickRecordingTest {

    /** A click executor that holds every task until the test releases it. */
    static final class HeldExecutor implements Executor {
        final List<Runnable> held = new ArrayList<>();

        @Override
        public synchronized void execute(Runnable task) {
            held.add(task);
        }

        synchronized void releaseAll() {
            held.forEach(Runnable::run);
            held.clear();
        }
    }

    @TestConfiguration
    static class HeldClicks {
        @Bean
        @Primary
        HeldExecutor heldClickExecutor() {
            return new HeldExecutor();
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    LinkService service;

    @Autowired
    HeldExecutor clicks;

    @Test
    @Tag("AC-analytics-2")
    void theRedirectIsAnsweredBeforeTheClickIsRecorded() {
        Link link = service.create("https://example.com/held", "held-owner", null, null, null).link();

        HttpResponse<String> response = new Http(port).get("/" + link.code());

        // The response is complete while the click is still queued: recording is off the request path.
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(clicks.held).hasSize(1);
        assertThat(service.getOwned(link.code(), "held-owner").clickCount()).isZero();
        clicks.releaseAll();
        assertThat(service.getOwned(link.code(), "held-owner").clickCount()).isEqualTo(1);
    }
}
