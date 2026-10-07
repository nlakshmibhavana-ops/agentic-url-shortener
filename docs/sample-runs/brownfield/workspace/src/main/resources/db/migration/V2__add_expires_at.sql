-- v0.5 (LINK-150). Additive and nullable, so every v0.4 row keeps working unchanged.
ALTER TABLE urls ADD COLUMN expires_at TIMESTAMP WITH TIME ZONE;
