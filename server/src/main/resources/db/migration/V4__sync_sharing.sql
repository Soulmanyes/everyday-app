-- V4: reminder sync and sharing (Phase 4).
-- updated_at on synced rows now holds the phone's clock (last write wins), set by the API.

-- "Remind me too": whether the owner of a shared reminder gets it as well.
ALTER TABLE reminder
    ADD COLUMN owner_receives BOOLEAN NOT NULL DEFAULT TRUE;

-- Invites removed by the owner (or by removing the member) are tombstoned so phones learn about it.
ALTER TABLE reminder_recipient
    ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX reminder_recipient_member_idx ON reminder_recipient (member_id);
CREATE INDEX reminder_state_member_idx ON reminder_state (member_id);

-- No phone has synced settings yet, so every row holds server defaults. Dating them at the epoch lets
-- the first setting pushed from a phone win (last write wins).
ALTER TABLE member_settings ALTER COLUMN updated_at SET DEFAULT to_timestamp(0);
UPDATE member_settings SET updated_at = to_timestamp(0);
