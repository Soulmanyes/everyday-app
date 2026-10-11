package app.everyday.android.push

import android.content.Context
import androidx.core.content.edit
import app.everyday.android.household.ApiClient
import app.everyday.android.household.Session
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Instant updates (Phase 8.5): tells the server this phone's FCM token, so it can send a "sync now"
 * push when something changes. Sent to every household the phone is in, once per (server, household
 * token, FCM token); a failure (no network, a server without the route) is retried on the next refresh.
 */
class PushRegistration(context: Context, private val session: Session) {
    private val prefs = context.getSharedPreferences("push", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    suspend fun ensureRegistered() = mutex.withLock {
        val memberships = session.current.memberships
        if (memberships.isEmpty()) return@withLock
        val fcmToken = fcmToken() ?: return@withLock
        val sent = prefs.getStringSet(KEY_SENT_ALL, emptySet())!!
        val now = memberships.mapNotNull { m ->
            val key = "${m.serverUrl}|${m.token}|$fcmToken".hashCode().toString()
            if (key in sent) return@mapNotNull key
            runCatching { ApiClient(m.serverUrl, m.token).setPushToken(fcmToken) }.getOrNull()?.let { key }
        }
        // Keys of households the phone has left go too.
        prefs.edit { putStringSet(KEY_SENT_ALL, now.toSet()) }
    }

    /** FCM gave the phone a new token: send it again everywhere. */
    fun forget() = prefs.edit { remove(KEY_SENT_ALL) }

    private suspend fun fcmToken(): String? = suspendCoroutine { cont ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            cont.resume(if (task.isSuccessful) task.result else null)
        }
    }

    private companion object {
        /** Sent registrations; 1.6.0 kept a single one under "sent". */
        const val KEY_SENT_ALL = "sent_all"
    }
}
