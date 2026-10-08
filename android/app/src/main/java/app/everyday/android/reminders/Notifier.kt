package app.everyday.android.reminders

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.everyday.android.MainActivity
import app.everyday.android.R
import app.everyday.android.data.MemberSettingsEntity
import app.everyday.android.data.ReminderEntity
import app.everyday.android.data.ReminderMode
import app.everyday.android.data.SoundMode

/**
 * Posts and cancels reminder notifications. Each reminder has at most one notification, identified
 * by the tag "<kind>:<reminderId>" and id [NOTIFICATION_ID].
 *
 * A channel's sound can't change after it is created, so there is one channel per
 * {timed, persistent} x {sound, vibrate, silent}, and the member's setting picks the channel.
 */
class Notifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun ensureChannels() {
        for (mode in ReminderMode.entries) for (sound in SoundMode.entries) {
            val channel = NotificationChannel(channelId(mode, sound), channelName(mode, sound), importance(mode, sound))
            when (sound) {
                SoundMode.SOUND -> channel.enableVibration(true)
                SoundMode.VIBRATE -> {
                    channel.setSound(null, null)
                    channel.enableVibration(true)
                }
                SoundMode.SILENT -> {
                    channel.setSound(null, null)
                    channel.enableVibration(false)
                }
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun canPost() = NotificationManagerCompat.from(context).areNotificationsEnabled()

    @SuppressLint("MissingPermission") // checked by canPost()
    fun showTimed(reminder: ReminderEntity, settings: MemberSettingsEntity) {
        if (!canPost()) return
        val notification = NotificationCompat.Builder(context, channelId(ReminderMode.TIMED, settings.timedSound))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(reminder.message)
            .setContentText("Reminder")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(reminder.id))
            .addAction(0, "Snooze ${settings.snoozeMinutes} min", action(ReminderReceiver.ACTION_SNOOZE, reminder.id))
            .build()
        NotificationManagerCompat.from(context).notify(tag(ReminderMode.TIMED, reminder.id), NOTIFICATION_ID, notification)
    }

    /**
     * Posts a persistent notification. [quiet] uses the silent channel, for re-posting after the user
     * swiped it away (Android 14+ lets users swipe ongoing notifications) without alerting again.
     */
    @SuppressLint("MissingPermission") // checked by canPost()
    fun showPersistent(reminder: ReminderEntity, settings: MemberSettingsEntity, quiet: Boolean) {
        if (!canPost()) return
        val sound = if (quiet) SoundMode.SILENT else settings.persistentSound
        val notification = NotificationCompat.Builder(context, channelId(ReminderMode.PERSISTENT, sound))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(reminder.message)
            .setContentText("Tap Done when finished")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(reminder.id))
            .setDeleteIntent(action(ReminderReceiver.ACTION_DISMISSED, reminder.id))
            .addAction(0, "Done", action(ReminderReceiver.ACTION_DONE, reminder.id))
            .build()
        NotificationManagerCompat.from(context).notify(tag(ReminderMode.PERSISTENT, reminder.id), NOTIFICATION_ID, notification)
    }

    /** Reminder ids that currently have a notification of [mode] showing. */
    fun showing(mode: ReminderMode): Set<String> {
        val prefix = "${mode.name}:"
        return manager.activeNotifications
            .mapNotNull { it.tag }
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .toSet()
    }

    fun cancel(mode: ReminderMode, reminderId: String) = manager.cancel(tag(mode, reminderId), NOTIFICATION_ID)

    private fun openApp(reminderId: String): PendingIntent = PendingIntent.getActivity(
        context,
        reminderId.hashCode(),
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(action: String, reminderId: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        (action + reminderId).hashCode(),
        Intent(context, ReminderReceiver::class.java).setAction(action).putExtra(ReminderReceiver.EXTRA_ID, reminderId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val NOTIFICATION_ID = 1

        fun tag(mode: ReminderMode, reminderId: String) = "${mode.name}:$reminderId"

        fun channelId(mode: ReminderMode, sound: SoundMode) = "${mode.name.lowercase()}_${sound.name.lowercase()}"

        private fun channelName(mode: ReminderMode, sound: SoundMode): String {
            val kind = if (mode == ReminderMode.TIMED) "Timed reminders" else "Persistent reminders"
            val how = when (sound) {
                SoundMode.SOUND -> "sound"
                SoundMode.VIBRATE -> "vibrate"
                SoundMode.SILENT -> "silent"
            }
            return "$kind ($how)"
        }

        private fun importance(mode: ReminderMode, sound: SoundMode) = when {
            sound == SoundMode.SILENT -> NotificationManager.IMPORTANCE_LOW
            mode == ReminderMode.TIMED -> NotificationManager.IMPORTANCE_HIGH
            else -> NotificationManager.IMPORTANCE_DEFAULT
        }
    }
}
