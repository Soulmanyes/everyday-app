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
import app.everyday.android.push.PushRegistration
import app.everyday.android.reminders.ActiveProfile
import app.everyday.android.reminders.ReminderEngine
import app.everyday.android.sync.SyncManager
import app.everyday.android.ui.AppPrefs
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
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
            householdIdChanges = pickedHousehold(),
        )
    }
    val choreRepository by lazy { ChoreRepository(database, pickedHousehold()) }
    val engine by lazy { ReminderEngine(this, repository, choreRepository, activeProfiles = { session.current.activeProfiles() }) }
    val household by lazy { HouseholdManager(this, session, repository, engine) }
    val chores by lazy { ChoreManager(session, choreRepository, repository, engine) }
    /** For flows shared beyond one screen. */
    val mainScope = MainScope()
    val updates = UpdateChecker()
    val prefs by lazy { AppPrefs(this) }
    val sync by lazy { SyncManager(this, session, database, engine) }
    val push by lazy { PushRegistration(this, session) }
    /** A join code from a join link, waiting for the join screen to take it (Phase 9). */
    val joinLink = MutableStateFlow<String?>(null)

    override fun onCreate() {
        super.onCreate()
        ChoreRefreshWorker.schedule(this)
        // Joining, picking or switching a profile pulls that profile's reminders.
        MainScope().launch {
            session.state.map { it.memberId?.takeIf { _ -> it.joined } }.distinctUntilChanged().collect { id ->
                if (id != null) sync.requestSync()
            }
        }
        // Joining (or rejoining) a household registers the phone there for instant updates, pending phones too.
        MainScope().launch {
            session.state.map { s -> s.memberships.map { it.token }.toSet() }.distinctUntilChanged().collect { tokens ->
                if (tokens.isNotEmpty()) push.ensureRegistered()
            }
        }
    }

    /**
     * Fetches everything that may have changed on the server: run by the 15-minute job and by the
     * server's "sync now" push.
     */
    suspend fun refreshFromServer() {
        // Picks up approval of this phone, new join requests (admin), and member changes.
        household.refresh()
        chores.refresh()
        runCatching { sync.sync() }
        push.ensureRegistered()
    }
}

/** The member whose reminders this phone shows: the picked profile, or the local one. */
private fun SessionState.profileId() = memberId?.takeIf { joined } ?: LOCAL_MEMBER_ID

/**
 * The members whose reminders fire: this phone's member in each household with notifications on, named
 * by household when there are several. Before joining, the local profile.
 */
private fun SessionState.activeProfiles(): List<ActiveProfile> {
    if (!joined) return listOf(ActiveProfile(LOCAL_MEMBER_ID))
    val several = memberships.size > 1
    return memberships.filter { it.notificationsOn && it.memberId != null }
        .map { ActiveProfile(it.memberId!!, it.householdName.takeIf { several }) }
}

private fun EverydayApplication.pickedHousehold() = session.state.map { it.householdId }.distinctUntilChanged()

val android.content.Context.app get() = applicationContext as EverydayApplication
