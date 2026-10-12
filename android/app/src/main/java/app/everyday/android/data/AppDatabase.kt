package app.everyday.android.data

import android.content.Context
import app.everyday.android.reminders.Window
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MemberEntity::class,
        MemberSettingsEntity::class,
        ReminderEntity::class,
        ReminderTimeEntity::class,
        ReminderWindowEntity::class,
        ReminderStateEntity::class,
        ChoreEntity::class,
        ChoreAssignmentEntity::class,
        ChoreCompletionEntity::class,
        ChoreSlotEntity::class,
        EventEntity::class,
        EventMemberEntity::class,
        BusyEventEntity::class,
    ],
    version = 11,
    exportSchema = true,
    // 1 -> 2 and 3 -> 4 add chore cache tables, 4 -> 5 the member colour, 6 -> 7 Busy events (Phase 7),
    // 7 -> 8 the household of members and chores (Phase 9), 9 -> 10 Everyone and sharing for scheduling (Phase 9.8).
    // MigrationTest checks reminders survive every step.
    autoMigrations = [
        AutoMigration(from = 1, to = 2), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8), AutoMigration(from = 8, to = 9), AutoMigration(from = 9, to = 10), AutoMigration(from = 10, to = 11),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun reminderDao(): ReminderDao
    abstract fun memberDao(): MemberDao
    abstract fun choreDao(): ChoreDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "everyday.db").addMigrations(MIGRATION_2_3, MIGRATION_5_6).build()
    }
}

/**
 * 2 -> 3 (Phase 4 sync): dirty flags, "Remind me too", done/snooze state per (reminder, member), and
 * invites. Existing household rows are marked dirty so the first sync uploads them; rows of the
 * local profile never sync. Existing state belongs to the reminder's owner.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `reminder` ADD COLUMN `ownerReceives` INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE `reminder` ADD COLUMN `dirty` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE `reminder` SET `dirty` = 1 WHERE `ownerMemberId` != '$LOCAL_MEMBER_ID'")
        db.execSQL("ALTER TABLE `member_settings` ADD COLUMN `dirty` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE `member_settings` SET `dirty` = 1 WHERE `memberId` != '$LOCAL_MEMBER_ID'")

        db.execSQL(
            "CREATE TABLE `reminder_state_new` (`reminderId` TEXT NOT NULL, `memberId` TEXT NOT NULL, `doneOn` TEXT, " +
                "`snoozeUntil` INTEGER, `updatedAt` INTEGER NOT NULL, `dirty` INTEGER NOT NULL, PRIMARY KEY(`reminderId`, `memberId`), " +
                "FOREIGN KEY(`reminderId`) REFERENCES `reminder`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "INSERT INTO `reminder_state_new` (`reminderId`, `memberId`, `doneOn`, `snoozeUntil`, `updatedAt`, `dirty`) " +
                "SELECT s.`reminderId`, r.`ownerMemberId`, s.`doneOn`, s.`snoozeUntil`, s.`updatedAt`, " +
                "CASE WHEN r.`ownerMemberId` != '$LOCAL_MEMBER_ID' THEN 1 ELSE 0 END " +
                "FROM `reminder_state` s JOIN `reminder` r ON r.`id` = s.`reminderId`",
        )
        db.execSQL("DROP TABLE `reminder_state`")
        db.execSQL("ALTER TABLE `reminder_state_new` RENAME TO `reminder_state`")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `reminder_recipient` (`reminderId` TEXT NOT NULL, `memberId` TEXT NOT NULL, " +
                "`status` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `dirty` INTEGER NOT NULL, PRIMARY KEY(`reminderId`, `memberId`), " +
                "FOREIGN KEY(`reminderId`) REFERENCES `reminder`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reminder_recipient_memberId` ON `reminder_recipient` (`memberId`)")
    }
}

/**
 * 5 -> 6 (Phase 6): events. General reminders become events with the same ids the server's V7 gives
 * them ([EventConversion]), invites are dropped, and SOUND (which also vibrated) becomes SOUND_VIBRATE.
 * The server makes the same changes, so only rows that were dirty stay dirty (including unsynced
 * deletes and declines).
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE `member_settings` SET `timedSound` = 'SOUND_VIBRATE' WHERE `timedSound` = 'SOUND'")
        db.execSQL("UPDATE `member_settings` SET `persistentSound` = 'SOUND_VIBRATE' WHERE `persistentSound` = 'SOUND'")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `event` (`id` TEXT NOT NULL, `ownerMemberId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                "`notes` TEXT, `weekdays` INTEGER NOT NULL, `onDate` TEXT, `startMinute` INTEGER, `endMinute` INTEGER, " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, `dirty` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_event_ownerMemberId` ON `event` (`ownerMemberId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `event_member` (`eventId` TEXT NOT NULL, `memberId` TEXT NOT NULL, `hidden` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, `dirty` INTEGER NOT NULL, PRIMARY KEY(`eventId`, `memberId`), " +
                "FOREIGN KEY(`eventId`) REFERENCES `event`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_event_member_memberId` ON `event_member` (`memberId`)")
        db.execSQL("ALTER TABLE `reminder` ADD COLUMN `eventId` TEXT")
        db.execSQL("ALTER TABLE `reminder` ADD COLUMN `leadMinutes` INTEGER")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reminder_eventId` ON `reminder` (`eventId`)")
        db.execSQL("ALTER TABLE `reminder_time` ADD COLUMN `relative` INTEGER NOT NULL DEFAULT 0")

        val result = EventConversion.convert(readGeneralReminders(db), readRemovedMembers(db))
        for (e in result.events) {
            db.execSQL(
                "INSERT INTO `event` (`id`, `ownerMemberId`, `title`, `notes`, `weekdays`, `onDate`, `startMinute`, `endMinute`, " +
                    "`createdAt`, `updatedAt`, `deleted`, `dirty`) VALUES (?, ?, ?, NULL, ?, NULL, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(e.id, e.ownerMemberId, e.title, e.weekdays, e.startMinute, e.endMinute, e.createdAt, e.updatedAt, e.deleted.sql, e.dirty.sql),
            )
        }
        for (m in result.members) {
            db.execSQL(
                "INSERT INTO `event_member` (`eventId`, `memberId`, `hidden`, `updatedAt`, `dirty`) VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any?>(m.eventId, m.memberId, m.hidden.sql, m.updatedAt, m.dirty.sql),
            )
        }
        for (r in result.updated) {
            db.execSQL(
                "UPDATE `reminder` SET `eventId` = ?, `message` = '', `weekdays` = 0, `active` = ?, `leadMinutes` = ?, `deleted` = ?, `dirty` = ? WHERE `id` = ?",
                arrayOf<Any?>(r.eventId, r.active.sql, r.leadMinutes, r.deleted.sql, r.dirty.sql, r.id),
            )
        }
        for (r in result.inserted) {
            db.execSQL(
                "INSERT INTO `reminder` (`id`, `ownerMemberId`, `message`, `mode`, `weekdays`, `active`, `choreId`, `createdAt`, " +
                    "`updatedAt`, `deleted`, `ownerReceives`, `dirty`, `eventId`, `leadMinutes`) VALUES (?, ?, '', ?, 0, ?, NULL, ?, ?, 0, 1, ?, ?, ?)",
                arrayOf<Any?>(r.id, r.ownerMemberId, r.mode.name, r.active.sql, r.createdAt, r.updatedAt, r.dirty.sql, r.eventId, r.leadMinutes),
            )
        }
        for (t in result.times) {
            db.execSQL("INSERT INTO `reminder_time` (`id`, `reminderId`, `minuteOfDay`, `relative`) VALUES (?, ?, ?, 0)", arrayOf<Any?>(t.id, t.reminderId, t.minuteOfDay))
        }
        for ((reminderId, memberId) in result.deletedStates) {
            db.execSQL("DELETE FROM `reminder_state` WHERE `reminderId` = ? AND `memberId` = ?", arrayOf<Any?>(reminderId, memberId))
        }
        for (st in result.states) {
            db.execSQL(
                "INSERT OR REPLACE INTO `reminder_state` (`reminderId`, `memberId`, `doneOn`, `snoozeUntil`, `updatedAt`, `dirty`) VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(st.reminderId, st.memberId, st.doneOn, st.snoozeUntil, st.updatedAt, st.dirty.sql),
            )
        }
        db.execSQL("DELETE FROM `reminder_window` WHERE `reminderId` IN (SELECT `id` FROM `reminder` WHERE `eventId` IS NOT NULL)")
        db.execSQL("DROP TABLE `reminder_recipient`")
    }

    private val Boolean.sql get() = if (this) 1 else 0

    private fun readRemovedMembers(db: SupportSQLiteDatabase): Set<String> =
        db.query("SELECT `id` FROM `member` WHERE `deleted` = 1").use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

    private fun readGeneralReminders(db: SupportSQLiteDatabase): List<EventConversion.OldReminder> {
        fun <T> rows(sql: String, read: (android.database.Cursor) -> T): List<T> =
            db.query(sql).use { c -> buildList { while (c.moveToNext()) add(read(c)) } }
        val times = rows("SELECT `reminderId`, `minuteOfDay` FROM `reminder_time`") { it.getString(0) to it.getInt(1) }
            .groupBy({ it.first }, { it.second })
        val windows = rows("SELECT `reminderId`, `startMinute`, `endMinute` FROM `reminder_window`") { it.getString(0) to Window(it.getInt(1), it.getInt(2)) }
            .groupBy({ it.first }, { it.second })
        val accepted = rows("SELECT `reminderId`, `memberId` FROM `reminder_recipient` WHERE `status` = 'ACCEPTED'") { it.getString(0) to it.getString(1) }
            .groupBy({ it.first }, { it.second })
        val declined = rows("SELECT `reminderId`, `memberId` FROM `reminder_recipient` WHERE `status` = 'DECLINED' AND `dirty` = 1") { it.getString(0) to it.getString(1) }
            .groupBy({ it.first }, { it.second })
        val states = rows("SELECT `reminderId`, `memberId`, `doneOn`, `snoozeUntil`, `updatedAt`, `dirty` FROM `reminder_state`") {
            ReminderStateEntity(it.getString(0), it.getString(1), it.getStringOrNull(2), it.getLongOrNull(3), it.getLong(4), it.getInt(5) != 0)
        }.groupBy { it.reminderId }
        return rows(
            "SELECT `id`, `ownerMemberId`, `message`, `mode`, `weekdays`, `active`, `ownerReceives`, `createdAt`, `updatedAt`, `dirty`, `deleted` " +
                "FROM `reminder` WHERE `choreId` IS NULL AND (`deleted` = 0 OR `dirty` = 1)",
        ) {
            val id = it.getString(0)
            EventConversion.OldReminder(
                id = id,
                ownerMemberId = it.getString(1),
                message = it.getString(2),
                mode = ReminderMode.valueOf(it.getString(3)),
                weekdays = it.getInt(4),
                active = it.getInt(5) != 0,
                ownerReceives = it.getInt(6) != 0,
                createdAt = it.getLong(7),
                updatedAt = it.getLong(8),
                dirty = it.getInt(9) != 0,
                times = times[id].orEmpty(),
                windows = windows[id].orEmpty(),
                accepted = accepted[id].orEmpty(),
                states = states[id].orEmpty(),
                deleted = it.getInt(10) != 0,
                declined = declined[id].orEmpty(),
            )
        }
    }

    private fun android.database.Cursor.getStringOrNull(i: Int) = if (isNull(i)) null else getString(i)

    private fun android.database.Cursor.getLongOrNull(i: Int) = if (isNull(i)) null else getLong(i)
}
