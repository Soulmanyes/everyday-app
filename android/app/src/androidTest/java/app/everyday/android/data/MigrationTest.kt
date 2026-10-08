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

    @Test
    fun migrate2To3MarksHouseholdRowsDirtyAndKeysStateByMember() {
        helper.createDatabase(DB, 2).apply {
            execSQL("INSERT INTO member (id, displayName, updatedAt, deleted) VALUES ('m1', 'Sam', 1, 0)")
            execSQL(
                "INSERT INTO reminder (id, ownerMemberId, message, mode, weekdays, active, choreId, createdAt, updatedAt, deleted) " +
                    "VALUES ('r1', 'm1', 'Water plants', 'PERSISTENT', 127, 1, NULL, 1, 5, 0)",
            )
            execSQL(
                "INSERT INTO reminder (id, ownerMemberId, message, mode, weekdays, active, choreId, createdAt, updatedAt, deleted) " +
                    "VALUES ('r2', 'local', 'Before joining', 'TIMED', 127, 1, NULL, 1, 5, 0)",
            )
            execSQL("INSERT INTO reminder_window (id, reminderId, startMinute, endMinute) VALUES ('w1', 'r1', 0, 1440)")
            execSQL("INSERT INTO reminder_state (reminderId, doneOn, snoozeUntil, updatedAt) VALUES ('r1', '2026-10-08', NULL, 7)")
            execSQL("INSERT INTO member_settings (memberId, snoozeMinutes, timedSound, persistentSound, updatedAt) VALUES ('m1', 15, 'SOUND', 'SILENT', 3)")
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 3, true, MIGRATION_2_3)
        db.query("SELECT id, dirty, ownerReceives FROM reminder ORDER BY id").use {
            it.moveToNext(); assertEquals(listOf("r1", 1, 1), listOf(it.getString(0), it.getInt(1), it.getInt(2)))
            it.moveToNext(); assertEquals(listOf("r2", 0, 1), listOf(it.getString(0), it.getInt(1), it.getInt(2)))
        }
        db.query("SELECT reminderId, memberId, doneOn, dirty FROM reminder_state").use {
            it.moveToFirst()
            assertEquals(listOf("r1", "m1", "2026-10-08", 1), listOf(it.getString(0), it.getString(1), it.getString(2), it.getInt(3)))
        }
        db.query("SELECT count(*) FROM reminder_window").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        db.query("SELECT dirty FROM member_settings WHERE memberId = 'm1'").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        db.query("SELECT count(*) FROM reminder_recipient").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
    }

    @Test
    fun migrate3To4AddsChoreSlotsAndKeepsReminders() {
        helper.createDatabase(DB, 3).apply {
            execSQL(
                "INSERT INTO reminder (id, ownerMemberId, message, mode, weekdays, active, choreId, createdAt, updatedAt, deleted, ownerReceives, dirty) " +
                    "VALUES ('r1', 'm1', 'Bins', 'TIMED', 127, 1, 'c1', 1, 5, 0, 1, 0)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(DB, 4, true)
        db.query("SELECT message, choreId FROM reminder").use {
            it.moveToFirst(); assertEquals(listOf("Bins", "c1"), listOf(it.getString(0), it.getString(1)))
        }
        db.query("SELECT count(*) FROM chore_slot").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
    }

    private companion object {
        const val DB = "migration-test"
    }
}
