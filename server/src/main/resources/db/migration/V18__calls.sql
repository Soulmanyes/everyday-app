-- V18: calls (Phase 12). The admin lets members ring other members' phones with a short message; each
-- person rung answers "on my way" or "can't now". The daily cleanup deletes calls after 7 days.
ALTER TABLE member ADD COLUMN can_call BOOLEAN NOT NULL DEFAULT false;

CREATE TABLE call (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id UUID NOT NULL REFERENCES household (id) ON DELETE CASCADE,
    caller_id    UUID NOT NULL REFERENCES member (id) ON DELETE CASCADE,
    message      TEXT NOT NULL CHECK (length(message) BETWEEN 1 AND 100),
    created_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX call_caller ON call (caller_id, created_at);
CREATE INDEX call_created ON call (created_at);

CREATE TABLE call_target (
    call_id     UUID NOT NULL REFERENCES call (id) ON DELETE CASCADE,
    member_id   UUID NOT NULL REFERENCES member (id) ON DELETE CASCADE,
    answer      TEXT CHECK (answer IN ('COMING', 'CANT')),
    answered_at TIMESTAMPTZ,
    PRIMARY KEY (call_id, member_id)
);

CREATE INDEX call_target_member ON call_target (member_id);
