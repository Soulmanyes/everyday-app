package app.everyday.android.household

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

const val DEFAULT_SERVER_URL = "https://everyday-home.duckdns.org"

/** What this phone knows about its household membership. */
data class SessionState(
    val serverUrl: String = DEFAULT_SERVER_URL,
    /** Device token from the server; null when the phone hasn't joined a household. */
    val token: String? = null,
    val householdName: String? = null,
    val adminMemberId: String? = null,
    /** The profile chosen on this phone; null until picked. */
    val memberId: String? = null,
    /** The user chose "Not now" on the welcome screen. */
    val skippedSetup: Boolean = false,
) {
    val joined get() = token != null
}

/**
 * Stores the session in app-private preferences (backups are disabled in the manifest, so the token
 * doesn't leave the phone) and exposes it as a flow for the UI.
 */
class Session(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    val current get() = _state.value

    fun update(transform: (SessionState) -> SessionState) = synchronized(this) {
        val next = transform(_state.value)
        prefs.edit {
            putString(KEY_URL, next.serverUrl)
            putString(KEY_TOKEN, next.token)
            putString(KEY_HOUSEHOLD, next.householdName)
            putString(KEY_ADMIN, next.adminMemberId)
            putString(KEY_MEMBER, next.memberId)
            putBoolean(KEY_SKIPPED, next.skippedSetup)
        }
        _state.value = next
    }

    /** Forgets the household (after leaving, or when the server no longer accepts the token). */
    fun clear() = update { SessionState(serverUrl = it.serverUrl) }

    private fun read() = SessionState(
        serverUrl = prefs.getString(KEY_URL, null) ?: DEFAULT_SERVER_URL,
        token = prefs.getString(KEY_TOKEN, null),
        householdName = prefs.getString(KEY_HOUSEHOLD, null),
        adminMemberId = prefs.getString(KEY_ADMIN, null),
        memberId = prefs.getString(KEY_MEMBER, null),
        skippedSetup = prefs.getBoolean(KEY_SKIPPED, false),
    )

    private companion object {
        const val KEY_URL = "server_url"
        const val KEY_TOKEN = "token"
        const val KEY_HOUSEHOLD = "household_name"
        const val KEY_ADMIN = "admin_member_id"
        const val KEY_MEMBER = "member_id"
        const val KEY_SKIPPED = "skipped_setup"
    }
}
