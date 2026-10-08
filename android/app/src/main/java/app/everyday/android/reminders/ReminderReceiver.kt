package app.everyday.android.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.everyday.android.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Handles the reminder alarm, notification actions, and system events that require rescheduling. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val engine = context.app.engine
        val id = intent.getStringExtra(EXTRA_ID)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_SNOOZE -> id?.let { engine.snooze(it) }
                    ACTION_DONE -> id?.let { engine.setDoneToday(it, true) }
                    ACTION_DISMISSED -> id?.let { engine.onPersistentDismissed(it) }
                    // ACTION_ALARM, boot, time and timezone changes, app updates, exact-alarm permission changes.
                    else -> engine.recompute()
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_ALARM = "app.everyday.android.action.ALARM"
        const val ACTION_SNOOZE = "app.everyday.android.action.SNOOZE"
        const val ACTION_DONE = "app.everyday.android.action.DONE"
        const val ACTION_DISMISSED = "app.everyday.android.action.DISMISSED"
        const val EXTRA_ID = "reminder_id"
    }
}
