package app.everyday.android.calls

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.edit
import app.everyday.android.household.ApiClient
import app.everyday.android.household.ApiError
import app.everyday.android.household.CallAnswer
import app.everyday.android.household.CallInfo
import app.everyday.android.household.Membership
import app.everyday.android.household.Session

/**
 * Calls (Phase 12): ringing other members' phones with a short message, ringing on this phone when
 * someone calls, and answering. The server's call pushes carry only the call's id, so this fetches the
 * call from whichever household it belongs to.
 */
class CallManager(private val context: Context, private val session: Session) {
    private val notifier = CallNotifier(context)
    private val prefs = context.getSharedPreferences("calls", Context.MODE_PRIVATE)

    private fun picked(): ApiClient = ApiClient(session.current.serverUrl, session.current.token)

    /** Members of the picked household a call can ring (they have a phone). */
    suspend fun targets(): Set<String> = picked().callTargets().toSet()

    /** Android 14+ needs the person to allow full-screen alerts; the app asks once, Settings keeps a switch. */
    fun shouldAskFullScreen(): Boolean = !notifier.canRingFullScreen() && !prefs.getBoolean(KEY_ASKED_FULL_SCREEN, false)

    fun askedFullScreen() = prefs.edit { putBoolean(KEY_ASKED_FULL_SCREEN, true) }

    fun canRingFullScreen() = notifier.canRingFullScreen()

    /** The last message this phone rang with, offered first next time. */
    val lastMessage: String? get() = prefs.getString(KEY_LAST_MESSAGE, null)

    /** Rings [memberIds] in the picked household. After the ringing, the caller is told who didn't answer. */
    suspend fun ring(memberIds: List<String>, message: String): CallInfo {
        val call = picked().startCall(memberIds, message)
        prefs.edit { putString(KEY_LAST_MESSAGE, message) }
        session.current.householdId?.let { scheduleCheck(it, call.id) }
        return call
    }

    /** A call push ([type] "call" or "call-answer") for [callId]. */
    suspend fun onPush(type: String, callId: String) {
        val (m, call) = find(callId) ?: return
        val me = m.memberId ?: return
        val mine = call.targets.firstOrNull { it.memberId == me }
        when {
            type == PUSH_CALL && mine != null && mine.answer == null -> {
                // A household with notifications off on this phone doesn't ring; the caller sees "No answer".
                // createdAt is the server's clock, so a phone whose clock is off still rings for a while; FCM
                // drops call pushes older than 30 s anyway.
                val left = (RING_MILLIS - (System.currentTimeMillis() - call.createdAt)).coerceIn(MIN_RING_MILLIS, RING_MILLIS)
                if (m.notificationsOn) notifier.ring(call, m.householdId!!, left)
            }
            type == PUSH_ANSWERED && mine?.answer != null -> notifier.stopRinging(call.id)
            type == PUSH_ANSWERED && call.callerId == me -> notifier.showAnswers(call, final = false)
        }
    }

    /** This phone's member answers a call in [householdId]. The ringing stops even if the server can't be reached. */
    suspend fun answer(householdId: String, callId: String, answer: CallAnswer) {
        notifier.stopRinging(callId)
        val m = session.current.memberships.firstOrNull { it.householdId == householdId } ?: return
        runCatching { ApiClient(m.serverUrl, m.token).answerCall(callId, answer) }
    }

    /** After the ringing: tells the caller who answered and who didn't. */
    suspend fun checkAnswers(householdId: String, callId: String) {
        val m = session.current.memberships.firstOrNull { it.householdId == householdId } ?: return
        val call = runCatching { ApiClient(m.serverUrl, m.token).getCall(callId) }.getOrNull() ?: return
        notifier.showAnswers(call, final = true)
    }

    /** The household this phone is in that has [callId]; other households answer 404. */
    private suspend fun find(callId: String): Pair<Membership, CallInfo>? {
        for (m in session.current.memberships.filter { it.memberId != null }) {
            try {
                return m to ApiClient(m.serverUrl, m.token).getCall(callId)
            } catch (_: ApiError) {
                // Not this household's call, or the server can't be reached.
            }
        }
        return null
    }

    private fun scheduleCheck(householdId: String, callId: String) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val at = System.currentTimeMillis() + RING_MILLIS + CHECK_GRACE_MILLIS
        val intent = PendingIntent.getBroadcast(
            context,
            callId.hashCode(),
            Intent(context, CallReceiver::class.java).setAction(CallReceiver.ACTION_CHECK)
                .putExtra(CallReceiver.EXTRA_CALL, callId).putExtra(CallReceiver.EXTRA_HOUSEHOLD, householdId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        }
    }

    companion object {
        const val PUSH_CALL = "call"
        const val PUSH_ANSWERED = "call-answer"
        /** How long a call rings. */
        const val RING_MILLIS = 30_000L
        private const val MIN_RING_MILLIS = 15_000L
        /** Answers tapped right at the end still reach the server before the caller is told. */
        private const val CHECK_GRACE_MILLIS = 5_000L
        private const val KEY_LAST_MESSAGE = "last_message"
        private const val KEY_ASKED_FULL_SCREEN = "asked_full_screen"

        /** Ready messages; the last one used comes first. */
        val QUICK_MESSAGES = listOf("Come downstairs", "Dinner's ready", "Call me")
    }
}
