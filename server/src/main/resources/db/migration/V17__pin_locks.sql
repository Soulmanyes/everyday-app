-- V17: wrong admin PINs lock in the database, so a restart or deploy doesn't reset them, and each lockout
-- within a day of the last one lasts longer (15 min, 1 h, 24 h). Keys: "join:<household>" for PINs typed
-- while joining (anyone with the join code or an invite) and "device:<device>" for an approved phone's admin
-- routes, so neither can lock the other out.
CREATE TABLE pin_lock (
    key          TEXT PRIMARY KEY,
    household_id UUID NOT NULL REFERENCES household (id) ON DELETE CASCADE,
    failures     INT NOT NULL DEFAULT 0,
    window_start TIMESTAMPTZ NOT NULL,
    locked_until TIMESTAMPTZ,
    strikes      INT NOT NULL DEFAULT 0
);

CREATE INDEX pin_lock_household ON pin_lock (household_id);

-- The last time wrong PINs locked anything in the household: the admin's phones are told.
ALTER TABLE household ADD COLUMN pin_locked_at TIMESTAMPTZ;
