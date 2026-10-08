-- V5: joining by name with admin approval. A device is bound to one member once approved.

ALTER TABLE device
    ADD COLUMN member_id      UUID REFERENCES member (id),
    ADD COLUMN status         TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved')),
    ADD COLUMN requested_name TEXT;

-- The phone that created the household belongs to the admin (on production that is the only device).
UPDATE device d SET member_id = h.admin_member_id, status = 'approved'
FROM household h
WHERE d.household_id = h.id AND d.created_at = h.created_at;

-- Any other existing phone becomes a join request for the admin to approve or link.
UPDATE device SET requested_name = coalesce(label, 'Unknown phone') WHERE member_id IS NULL;

CREATE INDEX device_member_idx ON device (member_id);
