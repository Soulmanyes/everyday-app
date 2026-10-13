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

    @Test
    fun migrate4To5AddsMemberColour() {
        helper.createDatabase(DB, 4).apply {
            execSQL("INSERT INTO member (id, displayName, updatedAt, deleted) VALUES ('m1', 'Sam', 1, 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate(DB, 5, true)
        db.query("SELECT displayName, color FROM member").use {
            it.moveToFirst()
            assertEquals("Sam", it.getString(0))
            assertEquals(true, it.isNull(1))
        }
    }

    @Test
    fun migrate5To6MovesSoundToSoundAndVibrate() {
        helper.createDatabase(DB, 5).apply {
            execSQL("INSERT INTO member_settings (memberId, snoozeMinutes, timedSound, persistentSound, updatedAt, dirty) VALUES ('m1', 15, 'SOUND', 'SOUND', 3, 0)")
            execSQL("INSERT INTO member_settings (memberId, snoozeMinutes, timedSound, persistentSound, updatedAt, dirty) VALUES ('m2', 15, 'VIBRATE', 'SILENT', 3, 0)")
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 6, true, MIGRATION_5_6)
        db.query("SELECT timedSound, persistentSound, dirty FROM member_settings ORDER BY memberId").use {
            it.moveToNext(); assertEquals(listOf("SOUND_VIBRATE", "SOUND_VIBRATE", 0), listOf(it.getString(0), it.getString(1), it.getInt(2)))
            it.moveToNext(); assertEquals(listOf("VIBRATE", "SILENT", 0), listOf(it.getString(0), it.getString(1), it.getInt(2)))
        }
    }

    @Test
    fun migrate5To6TurnsRemindersIntoEventsWithTheServersIds() {
        val r102 = "00000000-0000-0000-0000-000000000102"
        val sam = "00000000-0000-0000-0000-0000000000aa"
        val alex = "00000000-0000-0000-0000-0000000000bb"
        helper.createDatabase(DB, 5).apply {
            execSQL("INSERT INTO member (id, displayName, updatedAt, deleted) VALUES ('$sam', 'Sam', 1, 0), ('$alex', 'Alex', 1, 0)")
            execSQL(
                "INSERT INTO reminder (id, ownerMemberId, message, mode, weekdays, active, choreId, createdAt, updatedAt, deleted, ownerReceives, dirty) VALUES " +
                    "('$r102', '$sam', 'Homework', 'PERSISTENT', 31, 1, NULL, 1, 2, 0, 1, 0), " +
                    "('meds', '$sam', 'Meds', 'TIMED', 127, 1, NULL, 1, 2, 0, 1, 1), " +
                    "('chore', '$sam', 'Bins', 'TIMED', 8, 1, 'c1', 1, 2, 0, 1, 0)",
            )
            execSQL("INSERT INTO reminder_window (id, reminderId, startMinute, endMinute) VALUES ('w1', '$r102', 1080, 1140), ('w2', '$r102', 960, 1020)")
            execSQL("INSERT INTO reminder_time (id, reminderId, minuteOfDay) VALUES ('t1', 'meds', 480), ('t2', 'chore', 420)")
            execSQL("INSERT INTO reminder_recipient (reminderId, memberId, status, updatedAt, dirty) VALUES ('$r102', '$alex', 'ACCEPTED', 2, 0)")
            execSQL("INSERT INTO reminder_state (reminderId, memberId, doneOn, snoozeUntil, updatedAt, dirty) VALUES ('$r102', '$sam', '2026-10-09', NULL, 3, 0)")
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 6, true, MIGRATION_5_6)
        fun rows(sql: String) = db.query(sql).use { c ->
            buildList { while (c.moveToNext()) add((0 until c.columnCount).joinToString(" ") { if (c.isNull(it)) "-" else c.getString(it) }) }
        }
        val event1 = "270e5aed-f735-7f94-f429-68262aadf0d2"
        assertEquals(
            listOf("$r102 960 1020 0", "$event1 1080 1140 0", "meds - - 1").sorted(),
            rows("SELECT id, startMinute, endMinute, dirty FROM event").sorted(),
        )
        assertEquals(
            listOf(
                "$r102 $r102 0",
                "5f38c583-6b68-27c5-62b6-d21fab68a217 $event1 0",
                "bd44097f-5b70-3750-1fdb-29823e78cc46 $r102 0",
                "96e242e3-560d-0a09-6a8c-8ae9474fcca4 $event1 0",
                "meds meds -",
                "chore - -",
            ).sorted(),
            rows("SELECT id, eventId, leadMinutes FROM reminder").sorted(),
        )
        assertEquals(listOf("$r102 $alex", "$event1 $alex").sorted(), rows("SELECT eventId, memberId FROM event_member").sorted())
        assertEquals(listOf("5f38c583-6b68-27c5-62b6-d21fab68a217", r102).sorted(), rows("SELECT reminderId FROM reminder_state").sorted())
        assertEquals(listOf("0"), rows("SELECT count(*) FROM reminder_window"))
        assertEquals(listOf("chore 420", "meds 480"), rows("SELECT reminderId, minuteOfDay FROM reminder_time ORDER BY reminderId"))
    }

    @Test
    fun migrate6To7ShowsEventsAsBusyAndAddsBusyTable() {
        helper.createDatabase(DB, 6).apply {
            execSQL("INSERT INTO event (id, ownerMemberId, title, weekdays, createdAt, updatedAt, deleted, dirty) VALUES ('e1', 'm1', 'Gym', 1, 1, 1, 0, 0)")
            execSQL("INSERT INTO reminder (id, ownerMemberId, message, mode, weekdays, active, createdAt, updatedAt, deleted, ownerReceives, dirty, eventId) VALUES ('r1', 'm1', '', 'TIMED', 0, 1, 1, 1, 0, 1, 0, 'e1')")
            close()
        }
        val db = helper.runMigrationsAndValidate(DB, 7, true)
        db.query("SELECT title, showBusy FROM event").use {
            it.moveToFirst()
            assertEquals("Gym", it.getString(0))
            assertEquals(1, it.getInt(1))
        }
        db.query("SELECT count(*) FROM reminder").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        db.query("SELECT count(*) FROM busy_event").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
    }

    @Test
    fun migrate7To8KeepsMembersChoresAndRemindersWithoutAHousehold() {
        helper.createDatabase(DB, 7).apply {
            execSQL("INSERT INTO member (id, displayName, updatedAt, deleted, color) VALUES ('m1', 'Sam', 1, 0, 'blue')")
            execSQL("INSERT INTO chore (id, title, notes, weekdays, completion) VALUES ('c1', 'Bins', NULL, 1, 'ANY')")
            execSQL("INSERT INTO reminder (id, ownerMemberId, message, mode, weekdays, active, createdAt, updatedAt, deleted, ownerReceives, dirty, choreId) VALUES ('r1', 'm1', 'Bins', 'TIMED', 1, 1, 1, 1, 0, 1, 0, 'c1')")
            close()
        }
        val db = helper.runMigrationsAndValidate(DB, 8, true)
        db.query("SELECT displayName, color, householdId FROM member").use {
            it.moveToFirst()
            assertEquals("Sam", it.getString(0))
            assertEquals("blue", it.getString(1))
            assertEquals(true, it.isNull(2))
        }
        db.query("SELECT title, householdId FROM chore").use { it.moveToFirst(); assertEquals("Bins", it.getString(0)); assertEquals(true, it.isNull(1)) }
        db.query("SELECT count(*) FROM reminder").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
    }

    @Test
    fun migrate8To9KeepsEventsOnOneDayAndRemindersOverTheWholeEvent() {
        helper.createDatabase(DB, 8).apply {
            execSQL("INSERT INTO event (id, ownerMemberId, title, notes, weekdays, onDate, startMinute, endMinute, createdAt, updatedAt, deleted, dirty, showBusy) VALUES ('e1', 'm1', 'Gym', NULL, 1, NULL, 600, 660, 1, 1, 0, 0, 1)")
            execSQL("INSERT INTO busy_event (viewerMemberId, id, ownerMemberId, weekdays, onDate, startMinute, endMinute) VALUES ('m1', 'b1', 'm2', 1, NULL, 600, 660)")
            execSQL("INSERT INTO reminder (id, ownerMemberId, message, mode, weekdays, active, createdAt, updatedAt, deleted, ownerReceives, dirty, eventId) VALUES ('r1', 'm1', '', 'PERSISTENT', 0, 1, 1, 1, 0, 1, 0, 'e1')")
            close()
        }
        val db = helper.runMigrationsAndValidate(DB, 9, true)
        db.query("SELECT endDays, endMinute FROM event").use { it.moveToFirst(); assertEquals(0, it.getInt(0)); assertEquals(660, it.getInt(1)) }
        db.query("SELECT endDays FROM busy_event").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        db.query("SELECT eachDay FROM reminder").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
    }

    @Test
    fun migrate9To10KeepsEventsPersonalAndMembersTakingPart() {
        helper.createDatabase(DB, 9).apply {
            execSQL("INSERT INTO event (id, ownerMemberId, title, notes, weekdays, onDate, startMinute, endMinute, createdAt, updatedAt, deleted, dirty, showBusy, endDays) VALUES ('e1', 'm1', 'Gym', NULL, 1, NULL, 600, 660, 1, 1, 0, 0, 1, 0)")
            execSQL("INSERT INTO event_member (eventId, memberId, hidden, updatedAt, dirty) VALUES ('e1', 'm2', 0, 1, 0)")
            execSQL("INSERT INTO busy_event (viewerMemberId, id, ownerMemberId, weekdays, onDate, startMinute, endMinute, endDays) VALUES ('m1', 'b1', 'm2', 1, NULL, 600, 660, 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate(DB, 10, true)
        db.query("SELECT everyone FROM event").use { it.moveToFirst(); assertEquals("NONE", it.getString(0)) }
        db.query("SELECT role FROM event_member").use { it.moveToFirst(); assertEquals("MEMBER", it.getString(0)) }
        db.query("SELECT title, hiddenAt FROM busy_event").use { it.moveToFirst(); assertEquals(true, it.isNull(0)); assertEquals(true, it.isNull(1)) }
    }

    @Test
    fun migrate10To11LeavesChoresSharedWithNoCreator() {
        helper.createDatabase(DB, 10).apply {
            execSQL("INSERT INTO chore (id, title, notes, weekdays, completion, householdId) VALUES ('c1', 'Bins', NULL, 1, 'ANY', 'h1')")
            close()
        }
        val db = helper.runMigrationsAndValidate(DB, 11, true)
        db.query("SELECT createdBy, personal FROM chore").use { it.moveToFirst(); assertEquals(true, it.isNull(0)); assertEquals(0, it.getInt(1)) }
    }

    @Test
    fun migrate11To12KeepsEventsAndAddsAnEmptyImportTable() {
        helper.createDatabase(DB, 11).apply {
            execSQL("INSERT INTO event (id, ownerMemberId, title, notes, weekdays, onDate, startMinute, endMinute, createdAt, updatedAt, deleted, dirty, showBusy, endDays, everyone) VALUES ('e1', 'm1', 'Gym', NULL, 1, NULL, 600, 660, 1, 1, 0, 0, 1, 0, 'NONE')")
            close()
        }
        val db = helper.runMigrationsAndValidate(DB, 12, true)
        db.query("SELECT title FROM event").use { it.moveToFirst(); assertEquals("Gym", it.getString(0)) }
        db.query("SELECT COUNT(*) FROM imported_event").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
    }

    private companion object {
        const val DB = "migration-test"
    }
}
