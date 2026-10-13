-- V16: "before the start" reminder times go up to 7 days (Phase 10, for reminders imported from Google Calendar).
-- Clock times stay within a day. V2's check was unnamed, so Postgres called it reminder_time_minute_of_day_check.
ALTER TABLE reminder_time DROP CONSTRAINT reminder_time_minute_of_day_check;
ALTER TABLE reminder_time ADD CONSTRAINT reminder_time_minute_check
    CHECK (minute_of_day >= 0 AND minute_of_day <= CASE WHEN relative THEN 10080 ELSE 1439 END);
