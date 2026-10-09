-- V8: personal events can show to other members as "Busy" (Phase 7): times only, no title or notes.
-- The owner chooses per event; the default is Busy, for new and existing events. Shared events ignore it
-- (their members see them in full, and nobody else sees them).
ALTER TABLE event ADD COLUMN show_busy BOOLEAN NOT NULL DEFAULT TRUE;
