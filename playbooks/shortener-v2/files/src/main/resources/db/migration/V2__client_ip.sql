-- Q-PRIVACY = raw: store the client IP for unique-visitor counts.
ALTER TABLE clicks ADD COLUMN client_ip VARCHAR(45);
