-- V14: "Everyone" events and sharing for scheduling (Phase 9.8).
--
-- A member is on an event in one of two roles: MEMBER (takes part: on their timetable, with their own
-- reminders, as sharing worked before) or SCHEDULING (sees the title and times only). event.everyone gives
-- that role to every member of the household, including members who join later, except the owner, members
-- with their own live event_member row (that row's role wins) and members who left the event.
ALTER TABLE event ADD COLUMN everyone TEXT CHECK (everyone IN ('MEMBER', 'SCHEDULING'));

-- left_event: the member left (or declined, or hid) the event themselves, so Everyone doesn't bring it back.
-- A tombstone without it is the owner taking them off their list, and Everyone then still applies.
ALTER TABLE event_member
    ADD COLUMN role TEXT NOT NULL DEFAULT 'MEMBER' CHECK (role IN ('MEMBER', 'SCHEDULING')),
    ADD COLUMN left_event BOOLEAN NOT NULL DEFAULT FALSE;

-- Rows removed before now can't tell leaving from being taken off: count them as left, so switching an event
-- to Everyone never brings it back to someone who declined it.
UPDATE event_member SET left_event = TRUE WHERE deleted;

-- Members left out of a shared event now see it as Busy when show_busy is on (it was a hidden default of
-- TRUE). Events shared until now keep showing nothing to them.
UPDATE event e SET show_busy = FALSE
WHERE e.show_busy AND EXISTS (SELECT 1 FROM event_member em WHERE em.event_id = e.id AND NOT em.deleted);

-- The one place that says how a member is on an event: MEMBER, SCHEDULING, or NULL (not on it). Only members
-- of the owner's household who aren't removed and aren't the owner can be on it. Deleted events still answer,
-- so callers check event.deleted themselves.
CREATE FUNCTION event_role(p_event UUID, p_member UUID) RETURNS TEXT
LANGUAGE sql STABLE AS $$
    SELECT CASE
        WHEN em.member_id IS NOT NULL AND NOT em.deleted THEN em.role
        WHEN em.left_event THEN NULL
        ELSE e.everyone
    END
    FROM event e
    JOIN member o ON o.id = e.owner_member_id
    JOIN member m ON m.id = p_member AND m.household_id = o.household_id AND NOT m.deleted AND m.id <> e.owner_member_id
    LEFT JOIN event_member em ON em.event_id = e.id AND em.member_id = p_member
    WHERE e.id = p_event
$$;
