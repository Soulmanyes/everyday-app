-- V2: rebuild the schema with UUID ids and sync fields.
-- V1 was deployed with no data (checked 2026-10-08), so its tables are dropped instead of migrated.
-- IDs are UUIDs because phones create records offline (see CLAUDE.md). Synced rows carry
-- updated_at and a deleted flag (tombstone) instead of being removed.
-- Weekday masks: bit 0 = Monday ... bit 6 = Sunday. Minutes: 0..1439 since midnight, window end 1..1440.

DROP TABLE reminder_recipient, reminder_time, reminder, chore_assignment, chore, device, member, household;

CREATE TABLE household (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name             TEXT        NOT NULL,
    code_hash        TEXT        NOT NULL,               -- PBKDF2 of the household code
    timezone         TEXT        NOT NULL,               -- IANA zone, e.g. 'Europe/London'
    admin_member_id  UUID,                               -- set right after the admin member is created
    admin_pin_hash   TEXT        NOT NULL,               -- PBKDF2 of the admin PIN
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Exactly one household per server.
CREATE UNIQUE INDEX household_singleton ON household ((true));

CREATE TABLE member (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id  UUID        NOT NULL REFERENCES household (id) ON DELETE CASCADE,
    display_name  TEXT        NOT NULL,
    color         TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted       BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX member_name_unique ON member (household_id, lower(display_name)) WHERE NOT deleted;

ALTER TABLE household
    ADD CONSTRAINT household_admin_fk FOREIGN KEY (admin_member_id) REFERENCES member (id);

-- A phone that joined with the household code. Only a SHA-256 of its token is stored.
CREATE TABLE device (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id  UUID        NOT NULL REFERENCES household (id) ON DELETE CASCADE,
    token_hash    TEXT        NOT NULL UNIQUE,
    label         TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at  TIMESTAMPTZ,
    revoked       BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE TABLE member_settings (
    member_id         UUID        PRIMARY KEY REFERENCES member (id) ON DELETE CASCADE,
    snooze_minutes    INT         NOT NULL DEFAULT 10 CHECK (snooze_minutes BETWEEN 1 AND 240),
    timed_sound       TEXT        NOT NULL DEFAULT 'SOUND'  CHECK (timed_sound IN ('SOUND', 'VIBRATE', 'SILENT')),
    persistent_sound  TEXT        NOT NULL DEFAULT 'SILENT' CHECK (persistent_sound IN ('SOUND', 'VIBRATE', 'SILENT')),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE chore (
    id            UUID        PRIMARY KEY,
    household_id  UUID        NOT NULL REFERENCES household (id) ON DELETE CASCADE,
    title         TEXT        NOT NULL,
    notes         TEXT,
    weekdays      SMALLINT    NOT NULL CHECK (weekdays BETWEEN 1 AND 127),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted       BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE TABLE chore_assignment (
    chore_id    UUID        NOT NULL REFERENCES chore (id) ON DELETE CASCADE,
    member_id   UUID        NOT NULL REFERENCES member (id) ON DELETE CASCADE,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted     BOOLEAN     NOT NULL DEFAULT FALSE,
    PRIMARY KEY (chore_id, member_id)
);

-- Owned by one member. chore_id NULL = general reminder (personal unless shared via reminder_recipient);
-- otherwise a chore reminder, which the owner may only have while assigned to that chore (enforced in the API).
CREATE TABLE reminder (
    id               UUID        PRIMARY KEY,
    owner_member_id  UUID        NOT NULL REFERENCES member (id) ON DELETE CASCADE,
    chore_id         UUID        REFERENCES chore (id) ON DELETE CASCADE,
    message          TEXT        NOT NULL,
    mode             TEXT        NOT NULL CHECK (mode IN ('TIMED', 'PERSISTENT')),
    weekdays         SMALLINT    NOT NULL CHECK (weekdays BETWEEN 0 AND 127),
    active           BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted          BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE INDEX reminder_owner_idx ON reminder (owner_member_id);
CREATE INDEX reminder_chore_idx ON reminder (chore_id);

-- Times and windows are replaced as a whole with their reminder, so they need no sync fields of their own.
CREATE TABLE reminder_time (
    id             UUID     PRIMARY KEY,
    reminder_id    UUID     NOT NULL REFERENCES reminder (id) ON DELETE CASCADE,
    minute_of_day  SMALLINT NOT NULL CHECK (minute_of_day BETWEEN 0 AND 1439)
);

CREATE INDEX reminder_time_reminder_idx ON reminder_time (reminder_id);

CREATE TABLE reminder_window (
    id            UUID     PRIMARY KEY,
    reminder_id   UUID     NOT NULL REFERENCES reminder (id) ON DELETE CASCADE,
    start_minute  SMALLINT NOT NULL CHECK (start_minute BETWEEN 0 AND 1439),
    end_minute    SMALLINT NOT NULL CHECK (end_minute BETWEEN 1 AND 1440),
    CHECK (end_minute > start_minute)
);

CREATE INDEX reminder_window_reminder_idx ON reminder_window (reminder_id);

-- Done/snooze state per member, so a shared reminder can be done for one person and not another.
CREATE TABLE reminder_state (
    reminder_id   UUID        NOT NULL REFERENCES reminder (id) ON DELETE CASCADE,
    member_id     UUID        NOT NULL REFERENCES member (id) ON DELETE CASCADE,
    done_on       DATE,
    snooze_until  TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (reminder_id, member_id)
);

-- Members invited to a general reminder. They receive it only once they accept.
CREATE TABLE reminder_recipient (
    reminder_id  UUID        NOT NULL REFERENCES reminder (id) ON DELETE CASCADE,
    member_id    UUID        NOT NULL REFERENCES member (id) ON DELETE CASCADE,
    status       TEXT        NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'accepted', 'declined')),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (reminder_id, member_id)
);
