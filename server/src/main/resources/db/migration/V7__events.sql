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
