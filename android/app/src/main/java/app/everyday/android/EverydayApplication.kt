package app.everyday.android

import android.app.Application
import app.everyday.android.chores.ChoreManager
import app.everyday.android.chores.ChoreRefreshWorker
import app.everyday.android.data.AppDatabase
import app.everyday.android.data.ChoreRepository
import app.everyday.android.data.LOCAL_MEMBER_ID
import app.everyday.android.data.ReminderRepository
import app.everyday.android.household.HouseholdManager
import app.everyday.android.household.Session
import app.everyday.android.household.SessionState
import app.everyday.android.reminders.ReminderEngine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Holds app-wide singletons. Small enough that no DI framework is needed. */
class EverydayApplication : Application() {
    val database by lazy { AppDatabase.create(this) }
    val session by lazy { Session(this) }
    val repository by lazy {
        ReminderRepository(
            database,
            currentMemberId = { session.current.profileId() },
            memberIdChanges = session.state.map { it.profileId() }.distinctUntilChanged(),
        )
    }
    val choreRepository by lazy { ChoreRepository(database) }
    val engine by lazy { ReminderEngine(this, repository, choreRepository, currentMemberId = { session.current.profileId() }) }
    val household by lazy { HouseholdManager(this, session, repository, engine) }
    val chores by lazy { ChoreManager(session, choreRepository, repository, engine) }

    override fun onCreate() {
        super.onCreate()
        ChoreRefreshWorker.schedule(this)
    }
}

/** The member whose reminders this phone shows and fires: the picked profile, or the local one. */
private fun SessionState.profileId() = memberId?.takeIf { joined } ?: LOCAL_MEMBER_ID

val android.content.Context.app get() = applicationContext as EverydayApplication
