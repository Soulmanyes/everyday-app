-- V7: events timetable (Phase 6).

-- Sound settings gain SOUND_VIBRATE, and SOUND becomes sound only. Members on SOUND had sound with
-- vibration until now, so they move to SOUND_VIBRATE, which is also the new default for timed reminders.
ALTER TABLE member_settings DROP CONSTRAINT member_settings_timed_sound_check;
ALTER TABLE member_settings DROP CONSTRAINT member_settings_persistent_sound_check;
UPDATE member_settings SET timed_sound = 'SOUND_VIBRATE' WHERE timed_sound = 'SOUND';
UPDATE member_settings SET persistent_sound = 'SOUND_VIBRATE' WHERE persistent_sound = 'SOUND';
ALTER TABLE member_settings
    ALTER COLUMN timed_sound SET DEFAULT 'SOUND_VIBRATE',
    ADD CONSTRAINT member_settings_timed_sound_check CHECK (timed_sound IN ('SOUND', 'VIBRATE', 'SOUND_VIBRATE', 'SILENT')),
    ADD CONSTRAINT member_settings_persistent_sound_check CHECK (persistent_sound IN ('SOUND', 'VIBRATE', 'SOUND_VIBRATE', 'SILENT'));

-- Events: things on the timetable. Weekly on `weekdays`, or once on `on_date` (exactly one of the two).
-- start/end are minutes since midnight; no start = all day, end is optional.
CREATE TABLE event (
    id               UUID        PRIMARY KEY,
    owner_member_id  UUID        NOT NULL REFERENCES member (id) ON DELETE CASCADE,
    title            TEXT        NOT NULL,
    notes            TEXT,
    weekdays         SMALLINT    NOT NULL DEFAULT 0 CHECK (weekdays BETWEEN 0 AND 127),
    on_date          DATE,
    start_minute     SMALLINT    CHECK (start_minute BETWEEN 0 AND 1439),
    end_minute       SMALLINT    CHECK (end_minute BETWEEN 1 AND 1440),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted          BOOLEAN     NOT NULL DEFAULT FALSE,
    CHECK ((on_date IS NULL) <> (weekdays = 0)),
    CHECK (end_minute IS NULL OR (start_minute IS NOT NULL AND end_minute > start_minute))
);

CREATE INDEX event_owner_idx ON event (owner_member_id);

-- Members a shared event is shared with (the owner has no row; no rows = personal). Only the owner
-- adds and removes rows (removed ones are tombstoned); a member sets only their own `hidden`.
CREATE TABLE event_member (
    event_id    UUID        NOT NULL REFERENCES event (id) ON DELETE CASCADE,
    member_id   UUID        NOT NULL REFERENCES member (id) ON DELETE CASCADE,
    hidden      BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted     BOOLEAN     NOT NULL DEFAULT FALSE,
    PRIMARY KEY (event_id, member_id)
);

CREATE INDEX event_member_member_idx ON event_member (member_id);

-- Every reminder now belongs to an event or a chore, and takes its days from it. An event reminder is
-- TIMED (reminder_time rows: fixed times, or with `relative` minutes before the start) or PERSISTENT
-- (lead_minutes NULL = all day, otherwise from lead_minutes before the start until the end).
ALTER TABLE reminder
    ADD COLUMN event_id UUID REFERENCES event (id) ON DELETE CASCADE,
    ADD COLUMN lead_minutes SMALLINT CHECK (lead_minutes BETWEEN 0 AND 1439),
    ADD CHECK (event_id IS NULL OR chore_id IS NULL);

CREATE INDEX reminder_event_idx ON reminder (event_id);

ALTER TABLE reminder_time ADD COLUMN relative BOOLEAN NOT NULL DEFAULT FALSE;

-- Convert each live general reminder into events. Phones make the same conversion in their own
-- database (Room 5 -> 6), so ids are derived, never random: the first event and the owner's reminder
-- keep the reminder's id, others are md5('<reminder id>:<suffix>') read as a UUID.
-- A timed reminder becomes one all-day weekly event with the same fixed times. Each window of a
-- persistent reminder becomes an event at that window (0-1440 = all day) with a reminder that runs
-- from the start until the end (or all day).
CREATE TEMP TABLE conv ON COMMIT DROP AS
SELECT r.id AS reminder_id, r.owner_member_id, r.message, r.mode, r.active, r.weekdays, r.created_at, r.updated_at,
       r.owner_receives AND NOT m.deleted AS owner_keeps,
       o.k, o.start_minute, o.end_minute
FROM reminder r
JOIN member m ON m.id = r.owner_member_id
CROSS JOIN LATERAL (
    SELECT (row_number() OVER (ORDER BY w.start_minute) - 1)::int AS k,
           CASE WHEN w.start_minute = 0 AND w.end_minute = 1440 THEN NULL ELSE w.start_minute END AS start_minute,
           CASE WHEN w.start_minute = 0 AND w.end_minute = 1440 THEN NULL ELSE w.end_minute END AS end_minute
    FROM reminder_window w WHERE w.reminder_id = r.id AND r.mode = 'PERSISTENT'
    UNION ALL
    -- Timed reminders, and persistent ones without windows (they never showed; their reminder is paused).
    SELECT 0, NULL, NULL WHERE r.mode = 'TIMED' OR NOT EXISTS (SELECT 1 FROM reminder_window w WHERE w.reminder_id = r.id)
) o
WHERE r.chore_id IS NULL AND NOT r.deleted;

ALTER TABLE conv
    ADD COLUMN event_id UUID,
    ADD COLUMN owner_reminder_id UUID,
    ADD COLUMN lead_minutes SMALLINT,
    ADD COLUMN reminder_active BOOLEAN;
UPDATE conv SET
    event_id = CASE WHEN k = 0 THEN reminder_id ELSE md5(reminder_id::text || ':event:' || k)::uuid END,
    owner_reminder_id = CASE WHEN k = 0 THEN reminder_id ELSE md5(reminder_id::text || ':reminder:' || k)::uuid END,
    lead_minutes = CASE WHEN mode = 'PERSISTENT' AND start_minute IS NOT NULL THEN 0 END,
    -- A reminder with no days, or a persistent one with no windows, never fired: it stays silent.
    reminder_active = active AND weekdays <> 0 AND (mode = 'TIMED' OR EXISTS (SELECT 1 FROM reminder_window w WHERE w.reminder_id = conv.reminder_id));

INSERT INTO event (id, owner_member_id, title, weekdays, start_minute, end_minute, created_at, updated_at)
SELECT event_id, owner_member_id, message, CASE WHEN weekdays = 0 THEN 127 ELSE weekdays END, start_minute, end_minute, created_at, updated_at
FROM conv;

-- Invitees who accepted share the event; pending and declined invites are dropped.
CREATE TEMP TABLE conv_member ON COMMIT DROP AS
SELECT c.*, rr.member_id
FROM conv c JOIN reminder_recipient rr ON rr.reminder_id = c.reminder_id AND NOT rr.deleted AND rr.status = 'accepted';

INSERT INTO event_member (event_id, member_id, updated_at)
SELECT event_id, member_id, updated_at FROM conv_member;

-- Invitees get their own copy of each reminder, with their own done/snooze state.
INSERT INTO reminder (id, owner_member_id, event_id, message, mode, weekdays, active, lead_minutes, created_at, updated_at)
SELECT md5(reminder_id::text || ':member:' || member_id::text || ':' || k)::uuid, member_id, event_id, '', mode, 0,
       reminder_active, lead_minutes, created_at, updated_at
FROM conv_member;

INSERT INTO reminder_time (id, reminder_id, minute_of_day)
SELECT gen_random_uuid(), md5(c.reminder_id::text || ':member:' || c.member_id::text || ':' || c.k)::uuid, t.minute_of_day
FROM conv_member c JOIN reminder_time t ON t.reminder_id = c.reminder_id
WHERE c.mode = 'TIMED';

INSERT INTO reminder_state (reminder_id, member_id, done_on, snooze_until, updated_at)
SELECT md5(c.reminder_id::text || ':member:' || c.member_id::text || ':' || c.k)::uuid, c.member_id, s.done_on, s.snooze_until, s.updated_at
FROM conv_member c JOIN reminder_state s ON s.reminder_id = c.reminder_id AND s.member_id = c.member_id;

DELETE FROM reminder_state s USING reminder r
WHERE r.id = s.reminder_id AND s.member_id <> r.owner_member_id AND r.chore_id IS NULL;

-- The owner's reminders on the extra windows' events.
INSERT INTO reminder (id, owner_member_id, event_id, message, mode, weekdays, active, lead_minutes, created_at, updated_at)
SELECT owner_reminder_id, owner_member_id, event_id, '', mode, 0, reminder_active, lead_minutes, created_at, updated_at
FROM conv WHERE k > 0 AND owner_keeps;

INSERT INTO reminder_state (reminder_id, member_id, done_on, snooze_until, updated_at)
SELECT c.owner_reminder_id, c.owner_member_id, s.done_on, s.snooze_until, s.updated_at
FROM conv c JOIN reminder_state s ON s.reminder_id = c.reminder_id AND s.member_id = c.owner_member_id
WHERE c.k > 0 AND c.owner_keeps;

-- The original row becomes the owner's reminder on the first event, or a tombstone if the owner
-- didn't get it ("Remind me too" off, or the owner was removed).
UPDATE reminder r SET event_id = c.event_id, message = '', weekdays = 0, active = c.reminder_active,
    lead_minutes = c.lead_minutes, deleted = NOT c.owner_keeps
FROM conv c WHERE c.reminder_id = r.id AND c.k = 0;

DELETE FROM reminder_window w USING reminder r WHERE r.id = w.reminder_id AND r.event_id IS NOT NULL;

DROP TABLE reminder_recipient;
ALTER TABLE reminder DROP COLUMN owner_receives;
