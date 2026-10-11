package app.everyday.server

import org.flywaydb.core.Flyway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MigrationTest {
    private val db get() = ApiTest.postgres.postgresDatabase

    @Test
    fun `V5 binds the household creator's phone to the admin and turns other phones into requests`() {
        db.connection.use { it.createStatement().execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;") }
        Flyway.configure().dataSource(db).target("4").load().migrate()
        db.connection.use { conn ->
            conn.createStatement().execute(
                """
                BEGIN;
                INSERT INTO household (id, name, code_hash, timezone, admin_pin_hash, created_at)
                    VALUES ('00000000-0000-0000-0000-000000000001', 'Home', 'x', 'Europe/London', 'y', '2026-10-08 21:00:00+00');
                INSERT INTO member (id, household_id, display_name) VALUES ('00000000-0000-0000-0000-0000000000aa', '00000000-0000-0000-0000-000000000001', 'bal');
                UPDATE household SET admin_member_id = '00000000-0000-0000-0000-0000000000aa';
                INSERT INTO device (household_id, token_hash, label, created_at)
                    VALUES ('00000000-0000-0000-0000-000000000001', 'h1', 'Phone', '2026-10-08 21:00:00+00'),
                           ('00000000-0000-0000-0000-000000000001', 'h2', 'Tablet', '2026-10-08 22:00:00+00');
                COMMIT;
                """.trimIndent(),
            )
        }
        migrate(db)
        db.connection.use { conn ->
            val rows = conn.createStatement().executeQuery("SELECT label, status, member_id, requested_name FROM device ORDER BY created_at").use { rs ->
                buildList { while (rs.next()) add(listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))) }
            }
            assertEquals(
                listOf(
                    listOf("Phone", "approved", "00000000-0000-0000-0000-0000000000aa", null),
                    listOf("Tablet", "pending", null, "Tablet"),
                ),
                rows,
            )
        }
    }

    @Test
    fun `V6 gives existing members distinct colours in creation order`() {
        db.connection.use { it.createStatement().execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;") }
        Flyway.configure().dataSource(db).target("5").load().migrate()
        db.connection.use { conn ->
            conn.createStatement().execute(
                """
                INSERT INTO household (id, name, code_hash, timezone, admin_pin_hash)
                    VALUES ('00000000-0000-0000-0000-000000000001', 'Home', 'x', 'Europe/London', 'y');
                INSERT INTO member (household_id, display_name, created_at, deleted) VALUES
                    ('00000000-0000-0000-0000-000000000001', 'bal', '2026-10-08 21:00:00+00', FALSE),
                    ('00000000-0000-0000-0000-000000000001', 'Gone', '2026-10-08 21:30:00+00', TRUE),
                    ('00000000-0000-0000-0000-000000000001', 'Test Alex', '2026-10-08 22:00:00+00', FALSE);
                """.trimIndent(),
            )
        }
        migrate(db)
        val colors = db.connection.use { conn ->
            conn.createStatement().executeQuery("SELECT display_name, color FROM member ORDER BY created_at").use { rs ->
                buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) }
            }
        }
        assertEquals(listOf("bal" to "blue", "Gone" to null, "Test Alex" to "red"), colors)
    }

    @Test
    fun `V7 moves SOUND to SOUND_VIBRATE and allows the new value`() {
        db.connection.use { it.createStatement().execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;") }
        Flyway.configure().dataSource(db).target("6").load().migrate()
        db.connection.use { conn ->
            conn.createStatement().execute(
                """
                INSERT INTO household (id, name, code_hash, timezone, admin_pin_hash)
                    VALUES ('00000000-0000-0000-0000-000000000001', 'Home', 'x', 'Europe/London', 'y');
                INSERT INTO member (id, household_id, display_name, created_at) VALUES
                    ('00000000-0000-0000-0000-0000000000aa', '00000000-0000-0000-0000-000000000001', 'bal', '2026-10-08 21:00:00+00'),
                    ('00000000-0000-0000-0000-0000000000bb', '00000000-0000-0000-0000-000000000001', 'Alex', '2026-10-08 22:00:00+00');
                INSERT INTO member_settings (member_id, timed_sound, persistent_sound) VALUES
                    ('00000000-0000-0000-0000-0000000000aa', 'SOUND', 'SOUND'),
                    ('00000000-0000-0000-0000-0000000000bb', 'VIBRATE', 'SILENT');
                """.trimIndent(),
            )
        }
        migrate(db)
        db.connection.use { conn ->
            val rows = conn.createStatement().executeQuery(
                "SELECT s.timed_sound, s.persistent_sound FROM member_settings s JOIN member m ON m.id = s.member_id ORDER BY m.created_at",
            ).use { rs -> buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) } }
            assertEquals(listOf("SOUND_VIBRATE" to "SOUND_VIBRATE", "VIBRATE" to "SILENT"), rows)
            conn.createStatement().execute("UPDATE member_settings SET timed_sound = 'SOUND', persistent_sound = 'SOUND_VIBRATE'")
        }
    }

    @Test
    fun `V7 turns general reminders into events with derived ids`() {
        db.connection.use { it.createStatement().execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;") }
        Flyway.configure().dataSource(db).target("6").load().migrate()
        // Sam (aa) has: 101 timed, two times; 102 persistent with two windows, shared with Alex (bb, accepted)
        // and Kim (cc, pending); 103 an FYI ("Remind me too" off) shared with Alex; 104 a chore reminder;
        // 105 deleted.
        db.connection.use { conn ->
            conn.createStatement().execute(
                """
                INSERT INTO household (id, name, code_hash, timezone, admin_pin_hash)
                    VALUES ('00000000-0000-0000-0000-000000000001', 'Home', 'x', 'Europe/London', 'y');
                INSERT INTO member (id, household_id, display_name) VALUES
                    ('00000000-0000-0000-0000-0000000000aa', '00000000-0000-0000-0000-000000000001', 'Sam'),
                    ('00000000-0000-0000-0000-0000000000bb', '00000000-0000-0000-0000-000000000001', 'Alex'),
                    ('00000000-0000-0000-0000-0000000000cc', '00000000-0000-0000-0000-000000000001', 'Kim');
                INSERT INTO chore (id, household_id, title, weekdays) VALUES ('00000000-0000-0000-0000-000000000c01', '00000000-0000-0000-0000-000000000001', 'Bins', 8);
                INSERT INTO reminder (id, owner_member_id, chore_id, message, mode, weekdays, active, owner_receives, created_at, updated_at, deleted) VALUES
                    ('00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-0000000000aa', NULL, 'Meds', 'TIMED', 127, TRUE, TRUE, '2026-10-01 10:00:00+00', '2026-10-02 10:00:00+00', FALSE),
                    ('00000000-0000-0000-0000-000000000102', '00000000-0000-0000-0000-0000000000aa', NULL, 'Homework', 'PERSISTENT', 31, TRUE, TRUE, '2026-10-01 10:00:00+00', '2026-10-02 10:00:00+00', FALSE),
                    ('00000000-0000-0000-0000-000000000103', '00000000-0000-0000-0000-0000000000aa', NULL, 'Out tonight', 'TIMED', 16, FALSE, FALSE, '2026-10-01 10:00:00+00', '2026-10-02 10:00:00+00', FALSE),
                    ('00000000-0000-0000-0000-000000000104', '00000000-0000-0000-0000-0000000000aa', '00000000-0000-0000-0000-000000000c01', 'Bins', 'TIMED', 8, TRUE, TRUE, '2026-10-01 10:00:00+00', '2026-10-02 10:00:00+00', FALSE),
                    ('00000000-0000-0000-0000-000000000105', '00000000-0000-0000-0000-0000000000aa', NULL, 'Gone', 'TIMED', 127, TRUE, TRUE, '2026-10-01 10:00:00+00', '2026-10-02 10:00:00+00', TRUE);
                INSERT INTO reminder_time (id, reminder_id, minute_of_day) VALUES
                    (gen_random_uuid(), '00000000-0000-0000-0000-000000000101', 480), (gen_random_uuid(), '00000000-0000-0000-0000-000000000101', 1200),
                    (gen_random_uuid(), '00000000-0000-0000-0000-000000000103', 1080), (gen_random_uuid(), '00000000-0000-0000-0000-000000000104', 420);
                INSERT INTO reminder_window (id, reminder_id, start_minute, end_minute) VALUES
                    (gen_random_uuid(), '00000000-0000-0000-0000-000000000102', 1080, 1140),
                    (gen_random_uuid(), '00000000-0000-0000-0000-000000000102', 960, 1020);
                INSERT INTO reminder_recipient (reminder_id, member_id, status) VALUES
                    ('00000000-0000-0000-0000-000000000102', '00000000-0000-0000-0000-0000000000bb', 'accepted'),
                    ('00000000-0000-0000-0000-000000000102', '00000000-0000-0000-0000-0000000000cc', 'pending'),
                    ('00000000-0000-0000-0000-000000000103', '00000000-0000-0000-0000-0000000000bb', 'accepted');
                INSERT INTO reminder_state (reminder_id, member_id, done_on) VALUES
                    ('00000000-0000-0000-0000-000000000102', '00000000-0000-0000-0000-0000000000aa', '2026-10-09'),
                    ('00000000-0000-0000-0000-000000000102', '00000000-0000-0000-0000-0000000000bb', '2026-10-08');
                """.trimIndent(),
            )
        }
        migrate(db)
        fun rows(sql: String) = db.connection.use { conn ->
            conn.createStatement().executeQuery(sql).use { rs ->
                buildList { while (rs.next()) add((1..rs.metaData.columnCount).joinToString(" ") { rs.getString(it) ?: "-" }) }
            }
        }
        val id = { n: String -> "00000000-0000-0000-0000-000000000$n" }
        // Same values as the Android Room migration test: derivedId("<reminder>:<suffix>") = md5 read as a UUID.
        val event1 = "270e5aed-f735-7f94-f429-68262aadf0d2" // 102:event:1
        val ownerReminder1 = "5f38c583-6b68-27c5-62b6-d21fab68a217" // 102:reminder:1
        val alex0 = "bd44097f-5b70-3750-1fdb-29823e78cc46" // 102:member:bb:0
        val alex1 = "96e242e3-560d-0a09-6a8c-8ae9474fcca4" // 102:member:bb:1
        val alexFyi = "c330d967-2b09-4aee-f97f-bf568d4c2443" // 103:member:bb:0

        assertEquals(
            listOf(
                "${id("101")} Meds 127 - -",
                "${id("102")} Homework 31 960 1020",
                "${id("103")} Out tonight 16 - -",
                "$event1 Homework 31 1080 1140",
            ).sorted(),
            rows("SELECT id, title, weekdays, start_minute, end_minute FROM event").sorted(),
        )
        assertEquals(
            listOf("${id("102")} 00000000-0000-0000-0000-0000000000bb", "$event1 00000000-0000-0000-0000-0000000000bb", "${id("103")} 00000000-0000-0000-0000-0000000000bb").sorted(),
            rows("SELECT event_id, member_id FROM event_member").sorted(),
        )
        assertEquals(
            listOf(
                "${id("101")} aa ${id("101")} TIMED t - f",
                "${id("102")} aa ${id("102")} PERSISTENT t 0 f",
                "${id("103")} aa ${id("103")} TIMED f - t",
                "${id("104")} aa - TIMED t - f",
                "${id("105")} aa - TIMED t - t",
                "$ownerReminder1 aa $event1 PERSISTENT t 0 f",
                "$alex0 bb ${id("102")} PERSISTENT t 0 f",
                "$alex1 bb $event1 PERSISTENT t 0 f",
                "$alexFyi bb ${id("103")} TIMED f - f",
            ).sorted(),
            rows(
                "SELECT id, right(owner_member_id::text, 2), coalesce(event_id::text, '-'), mode, active, lead_minutes, deleted FROM reminder",
            ).map { it.replace(" true", " t").replace(" false", " f") }.sorted(),
        )
        assertEquals(
            listOf("${id("101")} 480 f", "${id("101")} 1200 f", "${id("103")} 1080 f", "${id("104")} 420 f", "$alexFyi 1080 f").sorted(),
            rows("SELECT reminder_id, minute_of_day, relative FROM reminder_time").map { it.replace(" false", " f") }.sorted(),
        )
        assertEquals(
            listOf("${id("102")} aa 2026-10-09", "$ownerReminder1 aa 2026-10-09", "$alex0 bb 2026-10-08", "$alex1 bb 2026-10-08").sorted(),
            rows("SELECT reminder_id, right(member_id::text, 2), done_on FROM reminder_state").sorted(),
        )
        assertEquals(listOf("0"), rows("SELECT count(*) FROM reminder_window"))
    }

    @Test
    fun `V8 shows existing events as Busy`() {
        db.connection.use { it.createStatement().execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;") }
        Flyway.configure().dataSource(db).target("7").load().migrate()
        db.connection.use { conn ->
            conn.createStatement().execute(
                """
                INSERT INTO household (id, name, code_hash, timezone, admin_pin_hash)
                    VALUES ('00000000-0000-0000-0000-000000000001', 'Home', 'x', 'Europe/London', 'y');
                INSERT INTO member (id, household_id, display_name)
                    VALUES ('00000000-0000-0000-0000-0000000000aa', '00000000-0000-0000-0000-000000000001', 'Sam');
                INSERT INTO event (id, owner_member_id, title, weekdays, created_at, updated_at)
                    VALUES ('00000000-0000-0000-0000-000000000e01', '00000000-0000-0000-0000-0000000000aa', 'Gym', 1, now(), now());
                """.trimIndent(),
            )
        }
        migrate(db)
        val busy = db.connection.use { conn ->
            conn.createStatement().executeQuery("SELECT show_busy FROM event").use { rs -> rs.next(); rs.getBoolean(1) }
        }
        assertTrue(busy)
    }

    @Test
    fun `V13 keeps existing events on one day and reminders over the whole event`() {
        db.connection.use { it.createStatement().execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;") }
        Flyway.configure().dataSource(db).target("12").load().migrate()
        db.connection.use { conn ->
            conn.createStatement().execute(
                """
                INSERT INTO household (id, name, code_hash, timezone, admin_pin_hash)
                    VALUES ('00000000-0000-0000-0000-000000000001', 'Home', 'x', 'Europe/London', 'y');
                INSERT INTO member (id, household_id, display_name)
                    VALUES ('00000000-0000-0000-0000-0000000000aa', '00000000-0000-0000-0000-000000000001', 'Sam');
                INSERT INTO event (id, owner_member_id, title, weekdays, start_minute, end_minute, created_at, updated_at)
                    VALUES ('00000000-0000-0000-0000-000000000e01', '00000000-0000-0000-0000-0000000000aa', 'Gym', 1, 600, 660, now(), now());
                INSERT INTO reminder (id, owner_member_id, event_id, message, mode, weekdays, created_at, updated_at)
                    VALUES ('00000000-0000-0000-0000-000000000b01', '00000000-0000-0000-0000-0000000000aa',
                        '00000000-0000-0000-0000-000000000e01', '', 'PERSISTENT', 0, now(), now());
                """.trimIndent(),
            )
        }
        migrate(db)
        db.connection.use { conn ->
            conn.createStatement().executeQuery("SELECT end_days FROM event").use { rs -> rs.next(); assertEquals(0, rs.getInt(1)) }
            conn.createStatement().executeQuery("SELECT each_day FROM reminder").use { rs -> rs.next(); assertFalse(rs.getBoolean(1)) }
            // The end must still come after the start on the same day.
            val bad = runCatching {
                conn.createStatement().execute("UPDATE event SET end_minute = 500")
            }
            assertTrue(bad.isFailure)
        }
    }
}
