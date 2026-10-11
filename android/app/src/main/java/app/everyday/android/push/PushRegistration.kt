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
 * push when something changes. Sent once per (server, household token, FCM token); a failure (no
 * network, a server without the route) is retried on the next refresh.
 */
class PushRegistration(context: Context, private val session: Session) {
    private val prefs = context.getSharedPreferences("push", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    suspend fun ensureRegistered() = mutex.withLock {
        val current = session.current
        val token = current.token ?: return@withLock
        val fcmToken = fcmToken() ?: return@withLock
        val key = "${current.serverUrl}|$token|$fcmToken".hashCode().toString()
        if (prefs.getString(KEY_SENT, null) == key) return@withLock
        runCatching { ApiClient(current.serverUrl, token).setPushToken(fcmToken) }
            .onSuccess { prefs.edit { putString(KEY_SENT, key) } }
    }

    /** FCM gave the phone a new token: send it again. */
    fun forget() = prefs.edit { remove(KEY_SENT) }

    private suspend fun fcmToken(): String? = suspendCoroutine { cont ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            cont.resume(if (task.isSuccessful) task.result else null)
        }
    }

    private companion object {
        const val KEY_SENT = "sent"
    }
}
