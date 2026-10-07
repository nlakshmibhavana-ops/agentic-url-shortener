package com.example.linkly;

import java.sql.PreparedStatement;
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

    public String create(String url) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("INSERT INTO urls (url) VALUES (?)", new String[] {"ID"});
            ps.setString(1, url);
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
        Integer clicks = jdbc.queryForObject("SELECT clicks FROM urls WHERE code = ?", Integer.class, code);
        jdbc.update("UPDATE urls SET clicks = ? WHERE code = ?", clicks + 1, code);
    }
}
