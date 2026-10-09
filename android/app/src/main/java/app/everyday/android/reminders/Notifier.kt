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
 * Posts and cancels reminder notifications. Each timed reminder has at most one notification,
 * identified by the tag "TIMED:<reminderId>" and id [NOTIFICATION_ID]. Persistent reminders share
 * one ongoing notification with the tag [PERSISTENT_TAG].
 *
 * A channel's sound can't change after it is created, so there is one channel per
 * {timed, persistent} x [SoundMode], and the member's setting picks the channel. The ids carry a
 * version: when a channel's behaviour changes it needs a new id, because recreating a deleted
 * channel under its old id brings back its old settings.
 */
class Notifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun ensureChannels() {
        // 1.1.0 channels without a version; their "sound" one also vibrated.
        for (mode in ReminderMode.entries) for (old in listOf("sound", "vibrate", "silent")) {
            manager.deleteNotificationChannel("${mode.name.lowercase()}_$old")
        }
        manager.createNotificationChannel(
            NotificationChannel(INVITE_CHANNEL, "Shared reminder invites", NotificationManager.IMPORTANCE_DEFAULT),
        )
        for (mode in ReminderMode.entries) for (sound in SoundMode.entries) {
            val channel = NotificationChannel(channelId(mode, sound), channelName(mode, sound), importance(mode, sound))
            when (sound) {
                SoundMode.SOUND -> channel.enableVibration(false)
                SoundMode.SOUND_VIBRATE -> channel.enableVibration(true)
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
     * Posts the one ongoing notification that lists every persistent reminder showing now ([reminders],
     * at least one). Android 16 bundles an app's notifications into one collapsed block, so separate
     * notifications would hide all but one. Each reminder gets a Done button, up to [MAX_DONE_ACTIONS].
     *
     * [alert] is true when a reminder has just appeared and should make its sound. Otherwise an
     * update stays quiet, including a re-post after the user swiped it away (Android 14+ allows that).
     */
    @SuppressLint("MissingPermission") // checked by canPost()
    fun showPersistent(reminders: List<ReminderEntity>, settings: MemberSettingsEntity, alert: Boolean) {
        if (!canPost()) return
        val showing = isPersistentShowing()
        val sound = if (!alert && !showing) SoundMode.SILENT else settings.persistentSound
        val single = reminders.singleOrNull()
        val builder = NotificationCompat.Builder(context, channelId(ReminderMode.PERSISTENT, sound))
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOngoing(true)
            .setOnlyAlertOnce(!alert)
            .setContentIntent(openApp(PERSISTENT_TAG))
            .setDeleteIntent(action(ReminderReceiver.ACTION_DISMISSED, null))
        if (single != null) {
            builder.setContentTitle(single.message)
                .setContentText("Tap Done when finished")
                .addAction(0, "Done", action(ReminderReceiver.ACTION_DONE, single.id))
        } else {
            val style = NotificationCompat.InboxStyle()
            reminders.forEach { style.addLine("• ${it.message}") }
            if (reminders.size > MAX_DONE_ACTIONS) style.setSummaryText("Open the app to mark the rest done")
            builder.setContentTitle("${reminders.size} reminders")
                .setContentText(reminders.joinToString(", ") { it.message })
                .setStyle(style)
            reminders.take(MAX_DONE_ACTIONS).forEach {
                builder.addAction(0, "Done: ${it.message.take(DONE_LABEL_LENGTH)}", action(ReminderReceiver.ACTION_DONE, it.id))
            }
        }
        NotificationManagerCompat.from(context).notify(PERSISTENT_TAG, NOTIFICATION_ID, builder.build())
    }

    /** "Sam shared "Bins" with you": opens the app, where the invite is accepted or declined. */
    @SuppressLint("MissingPermission") // checked by canPost()
    fun showInvite(reminderId: String, message: String, ownerName: String?) {
        if (!canPost()) return
        ensureChannels()
        val notification = NotificationCompat.Builder(context, INVITE_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${ownerName ?: "Someone"} shared a reminder with you")
            .setContentText("\"$message\" · Open the app to opt in or out")
            .setAutoCancel(true)
            .setContentIntent(openApp("INVITE:$reminderId"))
            .build()
        NotificationManagerCompat.from(context).notify("INVITE:$reminderId", NOTIFICATION_ID, notification)
    }

    /** On the admin's phone: someone asked to join; approving needs the PIN in member management. */
    @SuppressLint("MissingPermission") // checked by canPost()
    fun showJoinRequests(count: Int, householdName: String) {
        if (!canPost()) return
        ensureChannels()
        val notification = NotificationCompat.Builder(context, INVITE_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (count == 1) "Someone wants to join $householdName" else "$count people want to join $householdName")
            .setContentText("Open Settings → Manage members to approve")
            .setAutoCancel(true)
            .setContentIntent(openApp(JOIN_TAG))
            .build()
        NotificationManagerCompat.from(context).notify(JOIN_TAG, NOTIFICATION_ID, notification)
    }

    fun cancelJoinRequests() = manager.cancel(JOIN_TAG, NOTIFICATION_ID)

    fun cancelInvite(reminderId: String) = manager.cancel("INVITE:$reminderId", NOTIFICATION_ID)

    fun isPersistentShowing() = manager.activeNotifications.any { it.tag == PERSISTENT_TAG }

    fun cancelPersistent() = manager.cancel(PERSISTENT_TAG, NOTIFICATION_ID)

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

    private fun openApp(key: String): PendingIntent = PendingIntent.getActivity(
        context,
        key.hashCode(),
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(action: String, reminderId: String?): PendingIntent = PendingIntent.getBroadcast(
        context,
        (action + reminderId.orEmpty()).hashCode(),
        Intent(context, ReminderReceiver::class.java).setAction(action).putExtra(ReminderReceiver.EXTRA_ID, reminderId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val NOTIFICATION_ID = 1
        const val PERSISTENT_TAG = "PERSISTENT:all"
        private const val INVITE_CHANNEL = "invites"
        private const val JOIN_TAG = "JOIN_REQUESTS"
        private const val MAX_DONE_ACTIONS = 3
        private const val CHANNEL_VERSION = "v2"
        private const val DONE_LABEL_LENGTH = 18

        fun tag(mode: ReminderMode, reminderId: String) = "${mode.name}:$reminderId"

        fun channelId(mode: ReminderMode, sound: SoundMode) = "${mode.name.lowercase()}_${sound.name.lowercase()}_$CHANNEL_VERSION"

        private fun channelName(mode: ReminderMode, sound: SoundMode): String {
            val kind = if (mode == ReminderMode.TIMED) "Timed reminders" else "Persistent reminders"
            val how = when (sound) {
                SoundMode.SOUND -> "sound"
                SoundMode.VIBRATE -> "vibrate"
                SoundMode.SOUND_VIBRATE -> "sound and vibrate"
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
