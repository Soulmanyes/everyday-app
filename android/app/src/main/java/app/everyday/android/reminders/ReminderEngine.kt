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
import app.everyday.android.data.ReminderView
import app.everyday.android.data.toSpec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A member on this phone whose reminders fire, and the household name to show on them (when the phone is in several). */
data class ActiveProfile(val memberId: String, val label: String? = null)

/**
 * Glue between [ReminderScheduler] and Android: loads reminders, posts what is due, syncs persistent
 * notifications, and keeps a single alarm for the next event. Reminders of every household whose
 * notifications are on fire (Phase 9). Call [recompute] after anything changes.
 */
class ReminderEngine(
    private val context: Context,
    private val repository: ReminderRepository,
    private val chores: ChoreRepository,
    private val activeProfiles: () -> List<ActiveProfile>,
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
        val profiles = activeProfiles()
        val reminders = profiles.flatMap { p -> repository.getReminders(p.memberId).map { it to p } }
        val byId = reminders.associate { (r, _) -> r.reminder.id to r }
        val labelOf = reminders.associate { (r, p) -> r.reminder.id to p.label }
        fun title(r: ReminderView) = labelOf[r.reminder.id]?.let { "$it · ${r.title}" } ?: r.title
        val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli)

        // Uses the cached chores, so alarms never wait for the network.
        val choreDone = profiles.associate { it.memberId to chores.doneDatesFor(it.memberId) }
        val specs = reminders.map { (r, p) -> r.toSpec(r.reminder.choreId?.let { choreDone[p.memberId]?.get(it) }.orEmpty()) }
        val result = ReminderScheduler.compute(specs, now, zone, lastCheck)
        // Each reminder alerts with its owner's settings. The combined persistent notification uses the picked profile's.
        val settingsOf = profiles.associate { it.memberId to repository.getSettings(it.memberId) }
        val settings = repository.getSettings()

        result.expiredSnoozes.forEach { repository.setSnooze(it, null, byId[it]?.memberId) }
        result.dueTimed.forEach { id ->
            byId[id]?.let { notifier.showTimed(Notifier.Shown(id, title(it)), settingsOf[it.memberId] ?: settings) }
        }

        // Timed notifications of deleted or paused reminders go away.
        for (id in notifier.showing(ReminderMode.TIMED)) {
            if (byId[id]?.reminder?.active != true) notifier.cancel(ReminderMode.TIMED, id)
        }

        // Persistent reminders share one notification listing all that are showing now. It alerts only
        // when a reminder appears that wasn't in it before (not when re-posted after a swipe).
        val shown = prefs.getStringSet(KEY_SHOWN, emptySet())!!
        val visible = result.visiblePersistent.mapNotNull { byId[it] }.sortedBy { it.reminder.createdAt }.map { Notifier.Shown(it.reminder.id, title(it)) }
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
        val owner = repository.getReminder(reminderId)?.reminder?.ownerMemberId
        val minutes = (owner?.let { repository.getSettings(it) } ?: repository.getSettings()).snoozeMinutes
        repository.setSnooze(reminderId, Instant.now().plusSeconds(minutes * 60L), owner)
        notifier.cancel(ReminderMode.TIMED, reminderId)
        recompute()
    }

    suspend fun setDoneToday(reminderId: String, done: Boolean) {
        val owner = repository.getReminder(reminderId)?.reminder?.ownerMemberId
        repository.setDone(reminderId, if (done) LocalDate.now() else null, owner)
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
