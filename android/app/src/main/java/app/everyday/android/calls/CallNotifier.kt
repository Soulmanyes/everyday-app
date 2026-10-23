package app.everyday.android.calls

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.everyday.android.MainActivity
import app.everyday.android.R
import app.everyday.android.household.CallAnswer
import app.everyday.android.household.CallInfo

/**
 * Notifications for calls (Phase 12). A ringing call is a full-screen alert ([CallActivity]) that rings
 * with the phone's ringtone until answered or for [CallManager.RING_MILLIS]. It follows the phone's silent
 * and Do Not Disturb settings. The caller gets one notification per call with the answers.
 *
 * Channel sounds can't change after creation, so the ids carry a version, like [app.everyday.android.reminders.Notifier]'s.
 */
class CallNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun ensureChannels() {
        val ringing = NotificationChannel(RING_CHANNEL, "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Someone in your household rings this phone"
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 600, 800, 600)
        }
        manager.createNotificationChannel(ringing)
        manager.createNotificationChannel(
            NotificationChannel(ANSWER_CHANNEL, "Call answers", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Who answered your call"
            },
        )
    }

    /** False on Android 14+ until the person allows full-screen alerts; the call then shows as a heads-up notification. */
    fun canRingFullScreen(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || manager.canUseFullScreenIntent()

    /** Rings for [call] in [householdId] for [forMillis]. */
    @SuppressLint("MissingPermission") // checked by areNotificationsEnabled()
    fun ring(call: CallInfo, householdId: String, forMillis: Long) {
        val compat = NotificationManagerCompat.from(context)
        if (!compat.areNotificationsEnabled()) return
        ensureChannels()
        val screen = PendingIntent.getActivity(
            context,
            call.id.hashCode(),
            CallActivity.intent(context, call, householdId, ringUntil = System.currentTimeMillis() + forMillis),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, RING_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${call.callerName} is calling")
            .setContentText(call.message)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setTimeoutAfter(forMillis)
            .setContentIntent(screen)
            .setFullScreenIntent(screen, true)
            .addAction(0, "On my way", answer(call.id, householdId, CallAnswer.COMING))
            .addAction(0, "Can't now", answer(call.id, householdId, CallAnswer.CANT))
            .build()
        // Rings and vibrates over and over until answered or timed out, like a phone call.
        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        compat.notify(ringTag(call.id), NOTIFICATION_ID, notification)
    }

    fun stopRinging(callId: String) {
        manager.cancel(ringTag(callId), NOTIFICATION_ID)
        CallActivity.finishShowing(callId)
    }

    /**
     * On the caller's phone: who answered. [final] is after the ringing, when anyone who hasn't answered
     * shows as "no answer"; before that they're left out.
     */
    @SuppressLint("MissingPermission") // checked by areNotificationsEnabled()
    fun showAnswers(call: CallInfo, final: Boolean) {
        val compat = NotificationManagerCompat.from(context)
        if (!compat.areNotificationsEnabled()) return
        val lines = call.targets.mapNotNull { t ->
            val what = when (t.answer) {
                CallAnswer.COMING -> "on my way"
                CallAnswer.CANT -> "can't now"
                null -> if (final) "no answer" else null
            }
            what?.let { "${t.displayName}: $it" }
        }
        if (lines.isEmpty()) return
        ensureChannels()
        val style = NotificationCompat.InboxStyle()
        lines.forEach { style.addLine(it) }
        val open = PendingIntent.getActivity(
            context,
            call.id.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, ANSWER_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(lines.singleOrNull() ?: call.message)
            .setContentText(lines.joinToString(", "))
            .setStyle(if (lines.size > 1) style else null)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            // Each answer updates the same notification; only a new answer makes a sound.
            .setOnlyAlertOnce(final)
            .setContentIntent(open)
            .build()
        compat.notify(answersTag(call.id), NOTIFICATION_ID, notification)
    }

    private fun answer(callId: String, householdId: String, answer: CallAnswer): PendingIntent = PendingIntent.getBroadcast(
        context,
        (callId + answer.name).hashCode(),
        Intent(context, CallReceiver::class.java).setAction(CallReceiver.ACTION_ANSWER)
            .putExtra(CallReceiver.EXTRA_CALL, callId).putExtra(CallReceiver.EXTRA_HOUSEHOLD, householdId)
            .putExtra(CallReceiver.EXTRA_ANSWER, answer.name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        const val NOTIFICATION_ID = 1
        const val RING_CHANNEL = "calls_v1"
        const val ANSWER_CHANNEL = "call_answers_v1"

        fun ringTag(callId: String) = "CALL:$callId"
        fun answersTag(callId: String) = "CALL_ANSWERS:$callId"
    }
}
