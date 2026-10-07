package com.example.shortener.service;

import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.domain.Click;
import com.example.shortener.domain.Errors;
import com.example.shortener.domain.Link;
import com.example.shortener.domain.LinkStats;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/** Business rules for creating, resolving and reporting on links. */
@Service
public class LinkService {

    static final int MAX_CODE_ATTEMPTS = 5;

    private final LinkRepository repo;
    private final CodeGenerator codes;
    private final ShortenerProperties props;
    private final Clock clock;
    private final byte[] visitorKey;
    private final TtlCache<Link> cache;

    public LinkService(LinkRepository repo, CodeGenerator codes, ShortenerProperties props, Clock clock) {
        this.repo = repo;
        this.codes = codes;
        this.props = props;
        this.clock = clock;
        this.visitorKey = props.analyticsKey().isEmpty() ? randomKey()
                : props.analyticsKey().getBytes(StandardCharsets.UTF_8);
        // Bounded staleness across instances: entries live at most redirectCacheTtlSeconds.
        this.cache = new TtlCache<>(props.redirectCacheSize(),
                Duration.ofSeconds(props.redirectCacheTtlSeconds()), clock);
    }

    public record Created(Link link, boolean created) {
    }

    public Created create(String url, String owner, String alias, Instant expiresAt, String idempotencyKey) {
        String target = UrlValidator.validate(url, props.maxUrlLength(), props.baseUrl(),
                props.allowPrivateTargets(), props.blockedDomains());
        Instant now = clock.instant();
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw new Errors.InvalidExpiry("expires_at must be in the future");
        }
        String fingerprint = sha256(target + "|" + alias + "|" + expiresAt);
        if (idempotencyKey != null) {
            Optional<String[]> previous = repo.findIdempotent(owner, idempotencyKey);
            if (previous.isPresent()) {
                if (!previous.get()[0].equals(fingerprint)) {
                    throw new Errors.IdempotencyConflict();
                }
                Optional<Link> replay = repo.find(previous.get()[1]);
                if (replay.isPresent()) {
                    return new Created(replay.get(), false);
                }
            }
        }
        Link link;
        if (alias != null) {
            link = new Link(codes.validateAlias(alias), target, owner, now, expiresAt, null, 0);
            if (!repo.insert(link)) {
                throw new Errors.AliasTaken(alias);
            }
        } else {
            link = null;
            for (int i = 0; i < MAX_CODE_ATTEMPTS && link == null; i++) {
                Link candidate = new Link(codes.generate(props.codeLength()), target, owner, now, expiresAt, null, 0);
                if (repo.insert(candidate)) {
                    link = candidate;
                }
            }
            if (link == null) {
                throw new IllegalStateException("could not allocate a unique code; increase code length");
            }
        }
        if (idempotencyKey != null) {
            repo.saveIdempotent(owner, idempotencyKey, fingerprint, link.code(), now);
        }
        return new Created(link, true);
    }

    /** Looks up a link for redirecting. */
    public Link resolve(String code) {
        Link link = cache.get(code).orElse(null);
        if (link == null) {
            // Misses are not cached, so a newly created alias works at once.
            link = repo.find(code).orElseThrow(() -> new Errors.NotFound(code));
            cache.put(code, link);
        }
        // Expiry and deletion are still checked on every hit, cached or not.
        if (link.isGone(clock.instant())) {
            throw new Errors.Gone(code);
        }
        return link;
    }

    /** Someone else's link is reported as missing, so codes cannot be probed. */
    public Link getOwned(String code, String owner) {
        return repo.find(code).filter(l -> l.owner().equals(owner)).orElseThrow(() -> new Errors.NotFound(code));
    }

    public void delete(String code, String owner) {
        getOwned(code, owner);
        repo.softDelete(code, clock.instant());
        cache.invalidate(code);
    }

    public void recordClick(String code, String referrer, String userAgent) {
        recordClick(code, referrer, userAgent, null);
    }

    public void recordClick(String code, String referrer, String userAgent, String clientIp) {
        String host = null;
        if (referrer != null) {
            try {
                host = URI.create(referrer).getHost();
            } catch (IllegalArgumentException e) {
                host = null;
            }
        }
        repo.recordClick(code, new Click(clock.instant(), host, userAgentFamily(userAgent),
                visitorHash(clientIp, userAgent)));
    }

    /** Keyed hash of (day, IP, user agent). The raw IP never leaves this method. */
    public String visitorHash(String clientIp, String userAgent) {
        if (clientIp == null || clientIp.isEmpty()) {
            return null;
        }
        String message = clock.instant().toString().substring(0, 10) + "|" + clientIp + "|"
                + (userAgent == null ? "" : userAgent);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(visitorKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 16);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    public LinkStats stats(String code, String owner, int days) {
        Link link = getOwned(code, owner);
        Instant since = clock.instant().minus(Duration.ofDays(days));
        return new LinkStats(link.code(), link.clickCount(), repo.dailyClicks(code, since),
                repo.topReferrers(code, 5), repo.uniqueVisitors(code, since));
    }

    public static String ownerId(String apiKey) {
        return sha256(apiKey).substring(0, 16);
    }

    static String userAgentFamily(String userAgent) {
        String ua = userAgent == null ? "" : userAgent.toLowerCase(Locale.ROOT);
        if (ua.isEmpty()) {
            return "unknown";
        }
        if (ua.contains("bot") || ua.contains("crawler") || ua.contains("spider") || ua.contains("preview")) {
            return "bot";
        }
        if (ua.contains("mobile") || ua.contains("android") || ua.contains("iphone")) {
            return "mobile";
        }
        return "desktop";
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
