-- This migration tests CREATE INDEX CONCURRENTLY
-- It should work without deadlock when transactional locks are disabled

CREATE INDEX CONCURRENTLY idx_users_email ON users(email);
CREATE INDEX CONCURRENTLY idx_users_username ON users(username);
