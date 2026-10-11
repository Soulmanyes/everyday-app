-- V9: instant updates (Phase 8.5). A phone registers its FCM token, and the server sends it a data-only
-- "sync now" push when something changes. Revoked phones keep no token.
ALTER TABLE device ADD COLUMN push_token TEXT;

CREATE INDEX device_push_token_idx ON device (push_token) WHERE push_token IS NOT NULL;
