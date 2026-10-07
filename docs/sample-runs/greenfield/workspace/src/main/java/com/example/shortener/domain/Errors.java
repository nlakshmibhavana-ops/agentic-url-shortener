package com.example.shortener.domain;

import org.springframework.http.HttpStatus;

/** Domain errors. Each carries the HTTP status and stable error code the API reports. */
public final class Errors {

    private Errors() {
    }

    public abstract static class ApiException extends RuntimeException {
        private final HttpStatus status;
        private final String code;

        protected ApiException(HttpStatus status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public HttpStatus status() {
            return status;
        }

        public String code() {
            return code;
        }
    }

    public static class InvalidUrl extends ApiException {
        public InvalidUrl(String message) {
            super(HttpStatus.BAD_REQUEST, "invalid_url", message);
        }
    }

    public static class InvalidAlias extends ApiException {
        public InvalidAlias(String message) {
            super(HttpStatus.BAD_REQUEST, "invalid_alias", message);
        }
    }

    public static class InvalidExpiry extends ApiException {
        public InvalidExpiry(String message) {
            super(HttpStatus.BAD_REQUEST, "invalid_expiry", message);
        }
    }

    public static class Unauthorized extends ApiException {
        public Unauthorized() {
            super(HttpStatus.UNAUTHORIZED, "unauthorized", "missing or invalid X-API-Key");
        }
    }

    public static class NotFound extends ApiException {
        public NotFound(String code) {
            super(HttpStatus.NOT_FOUND, "not_found", "no link '" + code + "'");
        }
    }

    public static class AliasTaken extends ApiException {
        public AliasTaken(String alias) {
            super(HttpStatus.CONFLICT, "alias_taken", "alias '" + alias + "' is taken");
        }
    }

    public static class Gone extends ApiException {
        public Gone(String code) {
            super(HttpStatus.GONE, "gone", "link '" + code + "' expired or was deleted");
        }
    }

    public static class IdempotencyConflict extends ApiException {
        public IdempotencyConflict() {
            super(HttpStatus.UNPROCESSABLE_CONTENT, "idempotency_conflict",
                    "Idempotency-Key reused with a different request");
        }
    }

    public static class RateLimited extends ApiException {
        private final long retryAfterSeconds;

        public RateLimited(long retryAfterSeconds) {
            super(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "too many requests");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }
}
