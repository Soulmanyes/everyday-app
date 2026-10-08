package app.everyday.android.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
    ],
    version = 2,
    exportSchema = true,
    // 1 -> 2 only adds the chore cache tables. MigrationTest checks reminders survive it.
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun reminderDao(): ReminderDao
    abstract fun memberDao(): MemberDao
    abstract fun choreDao(): ChoreDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "everyday.db").build()
    }
}
