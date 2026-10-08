package app.everyday.android.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Every Room version bump must keep existing reminders (see CLAUDE.md). */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun migrate1To2KeepsRemindersAndAddsEmptyChoreTables() {
        helper.createDatabase(DB, 1).apply {
            execSQL("INSERT INTO member (id, displayName, updatedAt, deleted) VALUES ('m1', 'Sam', 1, 0)")
            execSQL(
                "INSERT INTO reminder (id, ownerMemberId, message, mode, weekdays, active, choreId, createdAt, updatedAt, deleted) " +
                    "VALUES ('r1', 'm1', 'Water plants', 'TIMED', 127, 1, NULL, 1, 1, 0)",
            )
            execSQL("INSERT INTO reminder_time (id, reminderId, minuteOfDay) VALUES ('t1', 'r1', 480)")
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 2, true)
        db.query("SELECT message FROM reminder WHERE id = 'r1'").use {
            it.moveToFirst()
            assertEquals("Water plants", it.getString(0))
        }
        db.query("SELECT count(*) FROM reminder_time").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        for (table in listOf("chore", "chore_assignment", "chore_completion")) {
            db.query("SELECT count(*) FROM $table").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        }
    }

    private companion object {
        const val DB = "migration-test"
    }
}
