-- Initial schema. See CLAUDE.md "Domain model".
-- Weekday masks: bit 0 = Monday ... bit 6 = Sunday (valid range 1..127).

CREATE TABLE household (
    id          BIGSERIAL PRIMARY KEY,
    name        TEXT        NOT NULL,
    code_hash   TEXT        NOT NULL,              -- hash of the shared household code, never plain text
    timezone    TEXT        NOT NULL DEFAULT 'UTC', -- IANA zone, e.g. 'Europe/London'
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE member (
    id            BIGSERIAL PRIMARY KEY,
    household_id  BIGINT      NOT NULL REFERENCES household (id) ON DELETE CASCADE,
    display_name  TEXT        NOT NULL,
    color         TEXT,                             -- hex colour for UI, e.g. '#4caf50'
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (household_id, display_name)
);

-- A phone that joined the household with the code. Only the token hash is stored.
CREATE TABLE device (
    id            BIGSERIAL PRIMARY KEY,
    household_id  BIGINT      NOT NULL REFERENCES household (id) ON DELETE CASCADE,
    token_hash    TEXT        NOT NULL UNIQUE,
    label         TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at  TIMESTAMPTZ
);

CREATE TABLE chore (
    id            BIGSERIAL PRIMARY KEY,
    household_id  BIGINT      NOT NULL REFERENCES household (id) ON DELETE CASCADE,
    title         TEXT        NOT NULL,
    notes         TEXT,
    weekdays      SMALLINT    NOT NULL CHECK (weekdays BETWEEN 1 AND 127),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE chore_assignment (
    chore_id   BIGINT NOT NULL REFERENCES chore (id) ON DELETE CASCADE,
    member_id  BIGINT NOT NULL REFERENCES member (id) ON DELETE CASCADE,
    PRIMARY KEY (chore_id, member_id)
);

-- Owned by one member. chore_id NULL = general (personal) reminder;
-- otherwise a chore reminder, which the owner may only have while assigned to that chore
-- (enforced in the API, not the schema).
CREATE TABLE reminder (
    id               BIGSERIAL PRIMARY KEY,
    owner_member_id  BIGINT      NOT NULL REFERENCES member (id) ON DELETE CASCADE,
    chore_id         BIGINT      REFERENCES chore (id) ON DELETE CASCADE,
    message          TEXT        NOT NULL,
    weekdays         SMALLINT    NOT NULL CHECK (weekdays BETWEEN 1 AND 127),
    active           BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX reminder_owner_idx ON reminder (owner_member_id);
CREATE INDEX reminder_chore_idx ON reminder (chore_id);

-- Any number of times per reminder, as local wall-clock times in the household timezone.
CREATE TABLE reminder_time (
    id           BIGSERIAL PRIMARY KEY,
    reminder_id  BIGINT NOT NULL REFERENCES reminder (id) ON DELETE CASCADE,
    time_of_day  TIME   NOT NULL,
    UNIQUE (reminder_id, time_of_day)
);
