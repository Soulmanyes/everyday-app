-- V15: personal chores and who created each chore (Phase 9.9).
--
-- created_by: the member who created the chore, set once by the server from the device's member. Chores made
-- before V15 have none, so they count only for their assignees. It doesn't cascade: a removed member is a soft
-- delete, and deleting the household removes the chores with it.
ALTER TABLE chore
    ADD COLUMN created_by UUID REFERENCES member (id) ON DELETE SET NULL,
    -- personal: only the creator sees it. Allowed only while the creator is its only assignee.
    ADD COLUMN personal BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT chore_personal_has_creator CHECK (NOT personal OR created_by IS NOT NULL);
