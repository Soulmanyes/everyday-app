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
                    // ACTION_ALARM, boot, time and timezone changes, app updates, exact-alarm permission changes.
                    else -> engine.recompute()
                }
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Hides the reminder for today, then also ticks its chore (if it has one) for the picked profile.
     * Ticking needs the server; offline it is skipped and only the reminder is hidden. The time
     * limit keeps the receiver within the system's limit for goAsync().
     */
    private suspend fun onDone(context: Context, reminderId: String) {
        val app = context.app
        app.engine.setDoneToday(reminderId, true)
        app.sync.requestSync()
        val choreId = app.repository.getDraft(reminderId)?.choreId ?: return
        withTimeoutOrNull(8_000) { runCatching { app.chores.markDone(choreId) } }
    }

    companion object {
        const val ACTION_ALARM = "app.everyday.android.action.ALARM"
        const val ACTION_SNOOZE = "app.everyday.android.action.SNOOZE"
        const val ACTION_DONE = "app.everyday.android.action.DONE"
        const val ACTION_DISMISSED = "app.everyday.android.action.DISMISSED"
        const val EXTRA_ID = "reminder_id"
    }
}
