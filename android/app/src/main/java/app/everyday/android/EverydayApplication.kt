package app.everyday.android

import android.app.Application
import app.everyday.android.data.AppDatabase
import app.everyday.android.data.ReminderRepository
import app.everyday.android.reminders.ReminderEngine

/** Holds app-wide singletons. Small enough that no DI framework is needed. */
class EverydayApplication : Application() {
    val database by lazy { AppDatabase.create(this) }
    val repository by lazy { ReminderRepository(database) }
    val engine by lazy { ReminderEngine(this, repository) }
}

val android.content.Context.app get() = applicationContext as EverydayApplication
