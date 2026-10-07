package com.example.shortener.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Propagates X-Request-ID and writes one structured access-log line per request. */
@Component
public class RequestIdFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Request-ID";
    private static final String ATTRIBUTE = RequestIdFilter.class.getName();
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Logger LOG = LoggerFactory.getLogger("access");

    static String current(HttpServletRequest request) {
        Object id = request.getAttribute(ATTRIBUTE);
        return id == null ? null : id.toString();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        // Only well-formed ids are echoed back, so the header cannot be used for log injection.
        String id = incoming != null && SAFE_ID.matcher(incoming).matches()
                ? incoming : UUID.randomUUID().toString().replace("-", "");
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader(HEADER, id);
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            LOG.info("{\"event\":\"http_request\",\"request_id\":\"{}\",\"method\":\"{}\",\"path\":\"{}\","
                    + "\"status\":{},\"duration_ms\":{}}", id, request.getMethod(), request.getRequestURI(),
                    response.getStatus(), (System.nanoTime() - started) / 1_000_000.0);
        }
    }
}
