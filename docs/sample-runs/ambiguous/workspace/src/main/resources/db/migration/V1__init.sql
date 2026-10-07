-- Released migrations are immutable: change the schema with a new V<n>__ file.
CREATE TABLE links (
    code         VARCHAR(32)   PRIMARY KEY,
    target_url   VARCHAR(2048) NOT NULL,
    owner        VARCHAR(32)   NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at   TIMESTAMP WITH TIME ZONE,
    deleted_at   TIMESTAMP WITH TIME ZONE,
    click_count  BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX idx_links_owner ON links(owner);

CREATE TABLE clicks (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    code               VARCHAR(32) NOT NULL REFERENCES links(code),
    clicked_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    clicked_day        VARCHAR(10) NOT NULL,
    referrer_host      VARCHAR(255),
    user_agent_family  VARCHAR(16)
);
CREATE INDEX idx_clicks_code_day ON clicks(code, clicked_day);

CREATE TABLE idempotency_keys (
    owner        VARCHAR(32)  NOT NULL,
    idem_key     VARCHAR(128) NOT NULL,
    fingerprint  VARCHAR(64)  NOT NULL,
    code         VARCHAR(32)  NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (owner, idem_key)
);
