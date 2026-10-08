-- V3: chore completion types and "done today".
-- ANY = done for the day once anyone marks it; EACH = every assignee does their own part.
-- done_on is the date in the household timezone, computed by the API (not CURRENT_DATE, which is UTC on the server).
-- There is no history screen; rows are kept because they are tiny.

ALTER TABLE chore
    ADD COLUMN completion TEXT NOT NULL DEFAULT 'ANY' CHECK (completion IN ('ANY', 'EACH'));

CREATE INDEX chore_household_idx ON chore (household_id);
CREATE INDEX chore_assignment_member_idx ON chore_assignment (member_id);

-- For ANY chores the API allows at most one row per chore and day.
CREATE TABLE chore_completion (
    chore_id    UUID        NOT NULL REFERENCES chore (id) ON DELETE CASCADE,
    member_id   UUID        NOT NULL REFERENCES member (id) ON DELETE CASCADE,
    done_on     DATE        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (chore_id, member_id, done_on)
);

CREATE INDEX chore_completion_day_idx ON chore_completion (done_on);
