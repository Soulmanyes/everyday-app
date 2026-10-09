package app.everyday.server

import org.flywaydb.core.Flyway
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
