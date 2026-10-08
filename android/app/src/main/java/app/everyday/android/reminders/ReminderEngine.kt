package app.everyday.android.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.edit
import app.everyday.android.data.ReminderMode
import app.everyday.android.data.ReminderRepository
import app.everyday.android.data.toSpec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Glue between [ReminderScheduler] and Android: loads reminders, posts what is due, syncs persistent
 * notifications, and keeps a single alarm for the next event. Call [recompute] after anything changes.
 */
class ReminderEngine(private val context: Context, private val repository: ReminderRepository) {
    private val mutex = Mutex()
    private val notifier = Notifier(context)
    private val prefs = context.getSharedPreferences("reminder_engine", Context.MODE_PRIVATE)
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    suspend fun recompute() = mutex.withLock {
        repository.ensureLocalMember()
        notifier.ensureChannels()
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val reminders = repository.getReminders()
        val byId = reminders.associateBy { it.reminder.id }
        val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli)

        val result = ReminderScheduler.compute(reminders.map { it.toSpec() }, now, zone, lastCheck)
        val settings = repository.getSettings()

        result.expiredSnoozes.forEach { repository.setSnooze(it, null) }
        result.dueTimed.forEach { id -> byId[id]?.let { notifier.showTimed(it.reminder, settings) } }

        // Timed notifications of deleted or paused reminders go away.
        for (id in notifier.showing(ReminderMode.TIMED)) {
            if (byId[id]?.reminder?.active != true) notifier.cancel(ReminderMode.TIMED, id)
        }

        // Persistent notifications: show the visible ones, cancel the rest.
        val quiet = prefs.getStringSet(KEY_QUIET, emptySet())!!
        val showing = notifier.showing(ReminderMode.PERSISTENT)
        for (id in result.visiblePersistent) {
            if (id !in showing) byId[id]?.let { notifier.showPersistent(it.reminder, settings, quiet = id in quiet) }
        }
        for (id in showing - result.visiblePersistent) notifier.cancel(ReminderMode.PERSISTENT, id)

        prefs.edit {
            putLong(KEY_LAST_CHECK, now.toEpochMilli())
            // Forget the "re-post quietly" flag once a reminder's window is over.
            putStringSet(KEY_QUIET, quiet intersect result.visiblePersistent)
        }
        scheduleAlarm(result.nextWakeAt)
    }

    suspend fun snooze(reminderId: String) {
        val minutes = repository.getSettings().snoozeMinutes
        repository.setSnooze(reminderId, Instant.now().plusSeconds(minutes * 60L))
        notifier.cancel(ReminderMode.TIMED, reminderId)
        recompute()
    }

    suspend fun setDoneToday(reminderId: String, done: Boolean) {
        repository.setDone(reminderId, if (done) LocalDate.now() else null)
        recompute()
    }

    /** The user swiped away a persistent notification: re-post it quietly if it should still show. */
    suspend fun onPersistentDismissed(reminderId: String) {
        val quiet = prefs.getStringSet(KEY_QUIET, emptySet())!!
        prefs.edit { putStringSet(KEY_QUIET, quiet + reminderId) }
        recompute()
    }

    fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun scheduleAlarm(at: Instant?) {
        val intent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_ALARM),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (at == null) {
            alarmManager.cancel(intent)
            return
        }
        val millis = at.toEpochMilli()
        if (canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, intent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, intent)
        }
    }

    private companion object {
        const val KEY_LAST_CHECK = "last_check"
        const val KEY_QUIET = "quiet_persistent"
    }
}
