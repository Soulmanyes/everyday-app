-- V6: household-wide member dot colours, one member per colour. Values are palette keys; the app
-- maps them to actual colours (see MEMBER_COLORS in Api.kt and the app's palette).

-- Existing members get colours in the order they were created.
WITH ranked AS (
    SELECT id, row_number() OVER (PARTITION BY household_id ORDER BY created_at, id) AS n
    FROM member WHERE NOT deleted
)
UPDATE member m
SET color = (ARRAY['blue', 'red', 'purple', 'orange', 'yellow', 'pink', 'cyan', 'brown', 'indigo', 'grey', 'maroon', 'sky'])[r.n]
FROM ranked r
WHERE m.id = r.id;

CREATE UNIQUE INDEX member_color_unique ON member (household_id, color) WHERE NOT deleted AND color IS NOT NULL;
