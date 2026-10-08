package app.everyday.android.data

import android.content.Context
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
        ReminderRecipientEntity::class,
    ],
    version = 3,
    exportSchema = true,
    // 1 -> 2 only adds the chore cache tables. MigrationTest checks reminders survive every step.
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun reminderDao(): ReminderDao
    abstract fun memberDao(): MemberDao
    abstract fun choreDao(): ChoreDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "everyday.db").addMigrations(MIGRATION_2_3).build()
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
