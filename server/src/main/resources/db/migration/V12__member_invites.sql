-- V12: one-tap invite links into a household (Phase 9.5). An admin makes a one-time invite and shares it as a
-- link; joining with it works like joining with the join code (the admin still approves). The code is long and
-- random, so it's stored as a SHA-256 hash and looked up by it.
CREATE TABLE member_invite (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id    UUID        NOT NULL REFERENCES household (id) ON DELETE CASCADE,
    code_hash       TEXT        NOT NULL UNIQUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,
    used_at         TIMESTAMPTZ,
    cancelled       BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE INDEX member_invite_household_idx ON member_invite (household_id);
