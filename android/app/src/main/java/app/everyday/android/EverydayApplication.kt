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
import app.everyday.android.sync.SyncManager
import app.everyday.android.ui.AppPrefs
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
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
    /** For flows shared beyond one screen. */
    val mainScope = MainScope()
    val updates = UpdateChecker()
    val prefs by lazy { AppPrefs(this) }
    val sync by lazy { SyncManager(this, session, database, engine) }

    override fun onCreate() {
        super.onCreate()
        ChoreRefreshWorker.schedule(this)
        // Joining, picking or switching a profile pulls that profile's reminders.
        MainScope().launch {
            session.state.map { it.memberId?.takeIf { _ -> it.joined } }.distinctUntilChanged().collect { id ->
                if (id != null) sync.requestSync()
            }
        }
    }
}

/** The member whose reminders this phone shows and fires: the picked profile, or the local one. */
private fun SessionState.profileId() = memberId?.takeIf { joined } ?: LOCAL_MEMBER_ID

val android.content.Context.app get() = applicationContext as EverydayApplication
