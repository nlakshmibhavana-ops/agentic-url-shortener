package com.example.linkly;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** LINK-142: unsafe target URLs must be rejected with 400 and never stored. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:linkly-safety;DB_CLOSE_DELAY=-1")
class UrlSafetyTest {

    private static final List<String> UNSAFE = List.of(
            "javascript:alert(1)", "JAVASCRIPT:alert(1)", "data:text/html;base64,PHNjcmlwdD4=",
            "vbscript:msgbox(1)", "file:///etc/passwd", "//evil.example/path", "https://",
            "https://user:pw@bank.example/", "https://example.com/a b", "");

    @LocalServerPort
    int port;

    @Tag("AC-url_safety-1")
    @Tag("AC-url_safety-2")
    @Test
    void unsafeUrlsAreRejectedAndNothingIsStored() {
        Http http = new Http(port);
        for (String url : UNSAFE) {
            HttpResponse<String> resp = http.post("/shorten", "{\"url\":\"" + url + "\"}");
            assertThat(resp.statusCode()).as(url).isEqualTo(400);
        }
        // Codes are sequential in this legacy service: no rejected URL may have been inserted.
        String code = Http.field(http.post("/shorten", "{\"url\":\"https://Example.com/Path?q=1\"}").body(), "code");
        assertThat(code).isEqualTo("1");
        assertThat(http.get("/r/" + code).headers().firstValue("Location")).hasValue("https://Example.com/Path?q=1");
    }
}
