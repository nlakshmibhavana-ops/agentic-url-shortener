-- Q-PRIVACY = hashed: a daily-rotating keyed hash per visitor. The raw IP is never stored.
ALTER TABLE clicks ADD COLUMN visitor_hash VARCHAR(16);
CREATE INDEX idx_clicks_code_visitor ON clicks(code, visitor_hash);
