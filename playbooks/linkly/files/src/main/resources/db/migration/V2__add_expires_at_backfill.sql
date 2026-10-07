-- v0.5 (LINK-150): add expiry, and give existing links the default 30-day lifetime.
ALTER TABLE urls ADD COLUMN expires_at TIMESTAMP WITH TIME ZONE;
UPDATE urls SET expires_at = DATEADD('DAY', 30, created);
