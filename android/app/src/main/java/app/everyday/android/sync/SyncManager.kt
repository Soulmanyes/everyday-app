package app.everyday.android.sync

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.room.withTransaction
import app.everyday.android.data.AppDatabase
import app.everyday.android.data.RecipientStatus
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
 * Syncs the picked profile's reminders, done/snooze state, settings and invite answers with the
 * server (see CLAUDE.md, "Sync (Phase 4)"). Room stays the source for alarms; offline, changes stay
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

        val dirty = reminders.getDirtyOwned(me).map { it.toSynced() }
        val states = reminders.getDirtyStates(me)
        val settings = members.getSettings(me)?.takeIf { it.dirty }
        val answers = reminders.getDirtyAnswers(me)
        val pushed = Pushed(
            reminders = dirty.associate { it.reminder.id to it.reminder.updatedAt },
            states = states.associate { it.reminderId to it.updatedAt },
            answers = answers.associate { it.reminderId to it.updatedAt },
            settings = settings?.updatedAt,
        )

        val snapshot = try {
            ApiClient(state.serverUrl, state.token).sync(me, dirty, states, settings, answers)
        } catch (e: ApiError) {
            if (e.isUnauthorized) {
                session.clear()
                engine.recompute()
            }
            throw e
        }
        if (snapshot.rejected.isNotEmpty()) Log.w(TAG, "Server refused: ${snapshot.rejected}")

        db.withTransaction {
            val plan = SyncMerge.plan(me, reminders.getForSync(me), reminders.getStates(me), members.getSettings(me), pushed, snapshot)
            plan.saveReminders.forEach { r -> reminders.saveReminder(r.reminder, r.timeEntities(), r.windowEntities(), r.recipients) }
            plan.deleteLocal.forEach { reminders.deleteLocal(it) }
            plan.clearDirty.forEach { reminders.clearDirty(it) }
            reminders.upsertStates(plan.saveStates)
            plan.clearDirtyStates.forEach { reminders.clearStateDirty(it, me) }
            plan.saveSettings?.let { members.upsertSettings(it) }
            if (plan.clearDirtySettings) members.getSettings(me)?.let { members.upsertSettings(it.copy(dirty = false)) }
        }
        engine.recompute()
        announceInvites(me)
    }

    /** Posts a notification for each invite this profile hasn't been told about yet, and clears answered ones. */
    private suspend fun announceInvites(me: String) {
        val key = "announced_$me"
        val announced = prefs.getStringSet(key, emptySet())!!
        val shared = db.reminderDao().getForSync(me).filter { it.reminder.ownerMemberId != me && !it.reminder.deleted }
        val pending = shared.filter { r -> r.recipients.any { it.memberId == me && it.status == RecipientStatus.PENDING } }
        val names = db.memberDao().getAllMembers().associate { it.id to it.displayName }
        pending.filter { it.reminder.id !in announced }.forEach { r ->
            notifier.showInvite(r.reminder.id, r.reminder.message, names[r.reminder.ownerMemberId])
        }
        // Answered or withdrawn invites don't need their notification any more.
        (announced - pending.map { it.reminder.id }.toSet()).forEach { notifier.cancelInvite(it) }
        // Forget invites that are gone, so a later re-invite is announced again.
        prefs.edit { putStringSet(key, pending.map { it.reminder.id }.toSet() + (announced intersect shared.map { it.reminder.id }.toSet())) }
    }

    private companion object {
        const val TAG = "Sync"
    }
}
