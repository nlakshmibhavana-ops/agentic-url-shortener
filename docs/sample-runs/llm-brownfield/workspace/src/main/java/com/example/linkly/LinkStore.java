package com.example.linkly;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** SQL storage for linkly. */
@Repository
public class LinkStore {

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private final JdbcTemplate jdbc;

    public LinkStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static String encode(long n) {
        StringBuilder out = new StringBuilder();
        while (n > 0) {
            out.insert(0, ALPHABET.charAt((int) (n % 62)));
            n /= 62;
        }
        return out.isEmpty() ? "0" : out.toString();
    }

    public String create(String url, Instant expiresAt) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("INSERT INTO urls (url, expires_at) VALUES (?, ?)",
                    new String[] {"ID"});
            ps.setString(1, url);
            ps.setObject(2, expiresAt == null ? null : expiresAt.atOffset(ZoneOffset.UTC));
            return ps;
        }, keys);
        long id = keys.getKey().longValue();
        String code = encode(id);
        jdbc.update("UPDATE urls SET code = ? WHERE id = ?", code, id);
        return code;
    }

    public Optional<Map<String, Object>> lookup(String code) {
        try {
            return Optional.of(jdbc.queryForMap("SELECT * FROM urls WHERE code = ?", code));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void hit(String code) {
        // One atomic statement: the old read-then-write lost updates under concurrent clicks.
        jdbc.update("UPDATE urls SET clicks = clicks + 1 WHERE code = ?", code);
    }
}
