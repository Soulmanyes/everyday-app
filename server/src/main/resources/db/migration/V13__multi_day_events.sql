-- V13: multi-day events (Phase 9.6). An event ends end_days after the day it starts (0 = the same day, as
-- before); end_minute is then the end on that last day, so it can be at or before the start minute. One-off
-- events last up to 31 days; weekly ones end within 6 days (checked in the API, which knows the kind).
ALTER TABLE event ADD COLUMN end_days SMALLINT NOT NULL DEFAULT 0 CHECK (end_days BETWEEN 0 AND 31);
-- V7's "end after start" check (unnamed there, so Postgres called it event_check1) now holds only within a day.
ALTER TABLE event DROP CONSTRAINT event_check1;
ALTER TABLE event ADD CONSTRAINT event_end_check
    CHECK (end_minute IS NULL OR (start_minute IS NOT NULL AND (end_days > 0 OR end_minute > start_minute)));

-- A persistent reminder on a multi-day event shows over the whole event (false) or all day on each of its days.
ALTER TABLE reminder ADD COLUMN each_day BOOLEAN NOT NULL DEFAULT FALSE;
