package app.everyday.android.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.edit
import app.everyday.android.data.ChoreRepository
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
class ReminderEngine(
    private val context: Context,
    private val repository: ReminderRepository,
    private val chores: ChoreRepository,
    private val currentMemberId: () -> String,
) {
    private val mutex = Mutex()
    private val notifier = Notifier(context)
    private val prefs = context.getSharedPreferences("reminder_engine", Context.MODE_PRIVATE)
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    suspend fun recompute() = mutex.withLock {
        repository.ensureCurrentMember()
        notifier.ensureChannels()
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val reminders = repository.getReminders()
        val byId = reminders.associateBy { it.reminder.id }
        val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli)

        // Uses the cached chores, so alarms never wait for the network.
        val choreDone = chores.doneDatesFor(currentMemberId())
        val specs = reminders.map { r -> r.toSpec(r.reminder.choreId?.let { choreDone[it] }.orEmpty()) }
        val result = ReminderScheduler.compute(specs, now, zone, lastCheck)
        val settings = repository.getSettings()

        result.expiredSnoozes.forEach { repository.setSnooze(it, null) }
        result.dueTimed.forEach { id -> byId[id]?.let { notifier.showTimed(it.reminder, settings) } }

        // Timed notifications of deleted or paused reminders go away.
        for (id in notifier.showing(ReminderMode.TIMED)) {
            if (byId[id]?.reminder?.active != true) notifier.cancel(ReminderMode.TIMED, id)
        }

        // Persistent reminders share one notification listing all that are showing now. It alerts only
        // when a reminder appears that wasn't in it before (not when re-posted after a swipe).
        val shown = prefs.getStringSet(KEY_SHOWN, emptySet())!!
        val visible = result.visiblePersistent.mapNotNull { byId[it]?.reminder }.sortedBy { it.createdAt }
        // Persistent notifications from before they were combined into one.
        notifier.showing(ReminderMode.PERSISTENT).filter { it != "all" }.forEach { notifier.cancel(ReminderMode.PERSISTENT, it) }
        if (visible.isEmpty()) {
            notifier.cancelPersistent()
        } else {
            notifier.showPersistent(visible, settings, alert = visible.any { it.id !in shown })
        }

        prefs.edit {
            putLong(KEY_LAST_CHECK, now.toEpochMilli())
            putStringSet(KEY_SHOWN, result.visiblePersistent)
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

    /** The user swiped away the persistent notification: re-post it quietly if it should still show. */
    suspend fun onPersistentDismissed() = recompute()

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
        /** Persistent reminders in the notification at the last recompute, to tell which ones are new. */
        const val KEY_SHOWN = "shown_persistent"
    }
}
