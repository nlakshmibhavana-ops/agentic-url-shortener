package com.example.shortener.service;

import com.example.shortener.domain.Click;
import com.example.shortener.domain.Link;
import com.example.shortener.domain.LinkStats.DayCount;
import com.example.shortener.domain.LinkStats.HostCount;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Persistence for links and click events. */
@Repository
public class LinkRepository {

    private final JdbcClient jdbc;

    public LinkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static OffsetDateTime ts(Instant i) {
        return i == null ? null : i.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static Link map(ResultSet rs, int row) throws SQLException {
        return new Link(rs.getString("code"), rs.getString("target_url"), rs.getString("owner"),
                instant(rs, "created_at"), instant(rs, "expires_at"), instant(rs, "deleted_at"),
                rs.getLong("click_count"));
    }

    /** Inserts a link; returns false if the code is already taken. */
    public boolean insert(Link link) {
        try {
            jdbc.sql("INSERT INTO links (code, target_url, owner, created_at, expires_at) VALUES (?, ?, ?, ?, ?)")
                    .params(link.code(), link.targetUrl(), link.owner(), ts(link.createdAt()), ts(link.expiresAt()))
                    .update();
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public Optional<Link> find(String code) {
        return jdbc.sql("SELECT * FROM links WHERE code = ?").param(code).query(LinkRepository::map).optional();
    }

    public void softDelete(String code, Instant when) {
        jdbc.sql("UPDATE links SET deleted_at = ? WHERE code = ? AND deleted_at IS NULL")
                .params(ts(when), code).update();
    }

    /** One transaction, and an atomic increment: no lost updates under concurrent clicks. */
    @Transactional
    public void recordClick(String code, Click click) {
        jdbc.sql("INSERT INTO clicks (code, clicked_at, clicked_day, referrer_host, user_agent_family)"
                        + " VALUES (?, ?, ?, ?, ?)")
                .params(code, ts(click.clickedAt()), click.clickedAt().toString().substring(0, 10),
                        click.referrerHost(), click.userAgentFamily())
                .update();
        jdbc.sql("UPDATE links SET click_count = click_count + 1 WHERE code = ?").param(code).update();
    }

    public List<DayCount> dailyClicks(String code, Instant since) {
        return jdbc.sql("SELECT clicked_day, COUNT(*) AS n FROM clicks WHERE code = ? AND clicked_at >= ?"
                        + " GROUP BY clicked_day ORDER BY clicked_day")
                .params(code, ts(since))
                .query((rs, i) -> new DayCount(rs.getString("clicked_day"), rs.getLong("n"))).list();
    }

    public List<HostCount> topReferrers(String code, int limit) {
        return jdbc.sql("SELECT COALESCE(referrer_host, 'direct') AS host, COUNT(*) AS n FROM clicks"
                        + " WHERE code = ? GROUP BY COALESCE(referrer_host, 'direct') ORDER BY n DESC, host LIMIT ?")
                .params(code, limit)
                .query((rs, i) -> new HostCount(rs.getString("host"), rs.getLong("n"))).list();
    }

    public Optional<String[]> findIdempotent(String owner, String key) {
        return jdbc.sql("SELECT fingerprint, code FROM idempotency_keys WHERE owner = ? AND idem_key = ?")
                .params(owner, key)
                .query((rs, i) -> new String[] {rs.getString("fingerprint"), rs.getString("code")}).optional();
    }

    public void saveIdempotent(String owner, String key, String fingerprint, String code, Instant when) {
        try {
            jdbc.sql("INSERT INTO idempotency_keys (owner, idem_key, fingerprint, code, created_at)"
                            + " VALUES (?, ?, ?, ?, ?)")
                    .params(owner, key, fingerprint, code, ts(when)).update();
        } catch (DuplicateKeyException e) {
            // A concurrent request with the same key won; its row is equivalent.
        }
    }

    public boolean ping() {
        return jdbc.sql("SELECT 1").query(Integer.class).single() == 1;
    }
}
