package app.everyday.android.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.everyday.android.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Handles the reminder alarm, notification actions, and system events that require rescheduling. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val engine = context.app.engine
        val id = intent.getStringExtra(EXTRA_ID)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_SNOOZE -> id?.let { engine.snooze(it); context.app.sync.requestSync() }
                    ACTION_DONE -> id?.let { onDone(context, it) }
                    ACTION_DISMISSED -> engine.onPersistentDismissed()
                    ACTION_DECLINE -> id?.let { onDecline(context, it) }
                    // ACTION_ALARM, boot, time and timezone changes, app updates, exact-alarm permission changes.
                    else -> engine.recompute()
                }
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Hides the reminder for today, then also ticks its chore (if it has one) for the reminder's owner, in
     * their household (which may not be the picked one). Ticking needs the server; offline it is skipped and
     * only the reminder is hidden. The time limit keeps the receiver within the system's limit for goAsync().
     */
    private suspend fun onDone(context: Context, reminderId: String) {
        val app = context.app
        app.engine.setDoneToday(reminderId, true)
        app.sync.requestSync()
        val reminder = app.repository.getReminder(reminderId)?.reminder ?: return
        val choreId = reminder.choreId ?: return
        withTimeoutOrNull(8_000) { runCatching { app.chores.markDoneAs(choreId, reminder.ownerMemberId) } }
    }

    /** "Decline" on "Sam added you to …": removes this phone's member on the event (in any of its households). */
    private suspend fun onDecline(context: Context, eventId: String) {
        val app = context.app
        Notifier(context).cancelAddedToEvent(eventId)
        val mine = app.session.current.memberships.mapNotNull { it.memberId }.toSet()
        val me = app.repository.eventMemberIds(eventId).firstOrNull { it in mine }
        app.repository.leaveEvent(eventId, me)
        app.engine.recompute()
        app.sync.requestSync()
    }

    companion object {
        const val ACTION_ALARM = "app.everyday.android.action.ALARM"
        const val ACTION_SNOOZE = "app.everyday.android.action.SNOOZE"
        const val ACTION_DONE = "app.everyday.android.action.DONE"
        const val ACTION_DISMISSED = "app.everyday.android.action.DISMISSED"
        const val ACTION_DECLINE = "app.everyday.android.action.DECLINE"
        /** A reminder id, or an event id for [ACTION_DECLINE]. */
        const val EXTRA_ID = "reminder_id"
    }
}
