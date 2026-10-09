package app.everyday.android.sync

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.room.withTransaction
import app.everyday.android.data.AppDatabase
import app.everyday.android.household.ApiClient
import app.everyday.android.household.ApiError
import app.everyday.android.household.Session
import app.everyday.android.reminders.Notifier
import app.everyday.android.reminders.ReminderEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Syncs the picked profile's events, reminders, done/snooze state, settings and hidden events with
 * the server (see CLAUDE.md, "Sync"). Room stays the source for alarms; offline, changes stay
 * dirty and go up on the next sync.
 */
class SyncManager(
    context: Context,
    private val session: Session,
    private val db: AppDatabase,
    private val engine: ReminderEngine,
) {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notifier = Notifier(context)
    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)

    /** Starts a sync in the background, e.g. after a local edit. Failures are left for the next one. */
    fun requestSync() {
        scope.launch { runCatching { sync() } }
    }

    /** One round trip for the picked profile. Throws [ApiError] if the server can't be reached. */
    suspend fun sync() = mutex.withLock {
        val state = session.current
        val me = state.memberId?.takeIf { state.joined } ?: return@withLock
        val reminders = db.reminderDao()
        val members = db.memberDao()

        val events = reminders.getDirtyOwnedEvents(me).map { it.toSynced() }
        val hidden = reminders.getDirtyHidden(me)
        val dirty = reminders.getDirtyOwned(me).map { it.toSynced() }
        val states = reminders.getDirtyStates(me)
        val settings = members.getSettings(me)?.takeIf { it.dirty }
        val pushed = Pushed(
            events = events.associate { it.event.id to it.event.updatedAt },
            hidden = hidden.associate { it.eventId to it.updatedAt },
            reminders = dirty.associate { it.reminder.id to it.reminder.updatedAt },
            states = states.associate { it.reminderId to it.updatedAt },
            settings = settings?.updatedAt,
        )

        val snapshot = try {
            ApiClient(state.serverUrl, state.token).sync(me, events, hidden, dirty, states, settings)
        } catch (e: ApiError) {
            if (e.isUnauthorized) {
                session.clear()
                engine.recompute()
            }
            throw e
        }
        if (snapshot.rejected.isNotEmpty()) Log.w(TAG, "Server refused: ${snapshot.rejected}")

        db.withTransaction {
            val plan = SyncMerge.plan(
                me, reminders.getEventsForSync(), reminders.getForSync(), reminders.getStates(me), members.getSettings(me), pushed, snapshot,
            )
            plan.saveEvents.forEach { e -> reminders.saveEvent(e.event, e.members) }
            plan.deleteEvents.forEach { reminders.deleteEventLocal(it) }
            plan.clearDirtyEvents.forEach { reminders.clearEventDirty(it) }
            plan.saveReminders.forEach { r -> reminders.saveReminder(r.reminder, r.timeEntities(), r.windowEntities()) }
            plan.deleteLocal.forEach { reminders.deleteLocal(it) }
            plan.clearDirty.forEach { reminders.clearDirty(it) }
            reminders.upsertStates(plan.saveStates)
            plan.clearDirtyStates.forEach { reminders.clearStateDirty(it, me) }
            plan.saveSettings?.let { members.upsertSettings(it) }
            if (plan.clearDirtySettings) members.getSettings(me)?.let { members.upsertSettings(it.copy(dirty = false)) }
        }
        engine.recompute()
        announceSharedEvents(me)
    }

    /**
     * Posts "Sam added you to …" once for each event newly shared with this profile, and clears the
     * notification once the event is hidden or gone. The first time (after updating to events), the
     * events already shared are taken as announced, so converted reminders don't all notify.
     */
    private suspend fun announceSharedEvents(me: String) {
        val key = "announced_events_$me"
        val shared = db.reminderDao().getVisibleEvents(me).filter { it.event.ownerMemberId != me }
        val ids = shared.map { it.event.id }.toSet()
        val announced = prefs.getStringSet(key, null)
        if (announced == null) {
            prefs.edit { putStringSet(key, ids) }
            return
        }
        val names = db.memberDao().getAllMembers().associate { it.id to it.displayName }
        shared.filter { it.event.id !in announced }.forEach { e ->
            notifier.showAddedToEvent(e.event.id, e.event.title, names[e.event.ownerMemberId])
        }
        (announced - ids).forEach { notifier.cancelAddedToEvent(it) }
        prefs.edit { putStringSet(key, ids) }
    }

    private companion object {
        const val TAG = "Sync"
    }
}
