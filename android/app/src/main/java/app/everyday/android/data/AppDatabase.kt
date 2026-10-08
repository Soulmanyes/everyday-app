package app.everyday.android.data

import android.content.Context
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
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun reminderDao(): ReminderDao
    abstract fun memberDao(): MemberDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "everyday.db").build()
    }
}
