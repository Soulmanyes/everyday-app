package app.everyday.android.household

import android.content.Context
import app.everyday.android.BuildConfig
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** The real server for release builds, the test server for the dev app (see build.gradle.kts). */
val DEFAULT_SERVER_URL: String = BuildConfig.DEFAULT_SERVER_URL

/** This phone in one household (Phase 9: a phone can be in several). The device [token] identifies it. */
data class Membership(
    val serverUrl: String,
    val token: String,
    /** Null until the first refresh after updating from 1.x, which didn't store it. */
    val householdId: String? = null,
    val householdName: String? = null,
    val adminMemberId: String? = null,
    /** The member this phone is in this household; null until the admin approves it. */
    val memberId: String? = null,
    val requestedName: String? = null,
    val pendingRequests: Int = 0,
    /** Reminders and notifications from this household fire (switched on the household picker). */
    val notificationsOn: Boolean = true,
    /** False until the admin makes a join code (households from before 2.0): nobody new can join. */
    val hasJoinCode: Boolean = true,
) {
    val isAdmin get() = memberId != null && memberId == adminMemberId
}

/**
 * What this phone knows about its households. The flat fields are the **picked** household, the one the
 * app shows; [others] are the rest. Screens use the picked one; background work (refresh, sync, alarms,
 * pushes) goes through [memberships].
 */
data class SessionState(
    val serverUrl: String = DEFAULT_SERVER_URL,
    /** Device token from the server; null when the phone hasn't joined a household. */
    val token: String? = null,
    val householdName: String? = null,
    val adminMemberId: String? = null,
    /** The member this phone belongs to; null until the admin approves it. */
    val memberId: String? = null,
    /** The name this phone asked to join as, while waiting for approval. */
    val requestedName: String? = null,
    /** Join requests waiting for the admin (only known on the admin's phone). */
    val pendingRequests: Int = 0,
    /** The user chose "Not now" on the welcome screen. */
    val skippedSetup: Boolean = false,
    val householdId: String? = null,
    val notificationsOn: Boolean = true,
    val hasJoinCode: Boolean = true,
    /** The phone's other households. */
    val others: List<Membership> = emptyList(),
) {
    val joined get() = token != null

    /** The picked household, or null if the phone hasn't joined one. */
    val picked: Membership?
        get() = token?.let {
            Membership(serverUrl, it, householdId, householdName, adminMemberId, memberId, requestedName, pendingRequests, notificationsOn, hasJoinCode)
        }

    /** Every household this phone is in, the picked one first. */
    val memberships: List<Membership> get() = listOfNotNull(picked) + others

    /** This state with [m] as the picked household. */
    fun withPicked(m: Membership?) = copy(
        serverUrl = m?.serverUrl ?: serverUrl,
        token = m?.token,
        householdName = m?.householdName,
        adminMemberId = m?.adminMemberId,
        memberId = m?.memberId,
        requestedName = m?.requestedName,
        pendingRequests = m?.pendingRequests ?: 0,
        householdId = m?.householdId,
        notificationsOn = m?.notificationsOn ?: true,
        hasJoinCode = m?.hasJoinCode ?: true,
    )

    /** Changes the household with device [token], picked or not. */
    fun updatingMembership(token: String, transform: (Membership) -> Membership): SessionState =
        if (this.token == token) withPicked(transform(picked!!)) else copy(others = others.map { if (it.token == token) transform(it) else it })

    /** Shows the household with device [token]; the one shown before joins the others. */
    fun picking(token: String): SessionState {
        val m = others.firstOrNull { it.token == token } ?: return this
        return withPicked(m).copy(others = listOfNotNull(picked) + others.filter { it.token != token })
    }

    /** A newly joined household becomes the picked one; the one picked before stays among the others. */
    fun adding(m: Membership): SessionState =
        withPicked(m).copy(others = listOfNotNull(picked) + others.filter { it.token != m.token }, skippedSetup = false)

    /** Without the household with device [token]. If it was picked, the next one is; with none left, no household. */
    fun removing(token: String): SessionState =
        if (this.token == token) withPicked(others.firstOrNull()).copy(others = others.drop(1)) else copy(others = others.filter { it.token != token })
}

/**
 * Stores the session in app-private preferences (backups are disabled in the manifest, so tokens don't
 * leave the phone) and exposes it as a flow for the UI. The picked household keeps the keys 1.x used,
 * so updating keeps the phone joined.
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
            putString(KEY_REQUESTED, next.requestedName)
            putInt(KEY_REQUESTS, next.pendingRequests)
            putBoolean(KEY_SKIPPED, next.skippedSetup)
            putString(KEY_HOUSEHOLD_ID, next.householdId)
            putBoolean(KEY_NOTIFY, next.notificationsOn)
            putBoolean(KEY_HAS_JOIN_CODE, next.hasJoinCode)
            putString(KEY_OTHERS, JSONArray(next.others.map { it.toJson() }).toString())
        }
        _state.value = next
    }

    fun updateMembership(token: String, transform: (Membership) -> Membership) = update { it.updatingMembership(token, transform) }

    fun pick(token: String) = update { it.picking(token) }

    fun add(m: Membership) = update { it.adding(m) }

    /**
     * Forgets the household with device [token] (after leaving, or when the server no longer accepts the
     * token). With none left the app shows the welcome screen.
     */
    fun remove(token: String) = update { it.removing(token) }

    private fun read() = SessionState(
        serverUrl = prefs.getString(KEY_URL, null) ?: DEFAULT_SERVER_URL,
        token = prefs.getString(KEY_TOKEN, null),
        householdName = prefs.getString(KEY_HOUSEHOLD, null),
        adminMemberId = prefs.getString(KEY_ADMIN, null),
        memberId = prefs.getString(KEY_MEMBER, null),
        requestedName = prefs.getString(KEY_REQUESTED, null),
        pendingRequests = prefs.getInt(KEY_REQUESTS, 0),
        skippedSetup = prefs.getBoolean(KEY_SKIPPED, false),
        householdId = prefs.getString(KEY_HOUSEHOLD_ID, null),
        notificationsOn = prefs.getBoolean(KEY_NOTIFY, true),
        hasJoinCode = prefs.getBoolean(KEY_HAS_JOIN_CODE, true),
        others = prefs.getString(KEY_OTHERS, null)?.let { json ->
            runCatching { JSONArray(json).let { a -> (0 until a.length()).map { membership(a.getJSONObject(it)) } } }.getOrNull()
        }.orEmpty(),
    )

    private fun Membership.toJson() = JSONObject()
        .put("serverUrl", serverUrl).put("token", token).put("householdId", householdId).put("householdName", householdName)
        .put("adminMemberId", adminMemberId).put("memberId", memberId).put("requestedName", requestedName)
        .put("pendingRequests", pendingRequests).put("notificationsOn", notificationsOn).put("hasJoinCode", hasJoinCode)

    private fun membership(o: JSONObject) = Membership(
        serverUrl = o.getString("serverUrl"),
        token = o.getString("token"),
        householdId = o.optStringOrNull("householdId"),
        householdName = o.optStringOrNull("householdName"),
        adminMemberId = o.optStringOrNull("adminMemberId"),
        memberId = o.optStringOrNull("memberId"),
        requestedName = o.optStringOrNull("requestedName"),
        pendingRequests = o.optInt("pendingRequests"),
        notificationsOn = o.optBoolean("notificationsOn", true),
        hasJoinCode = o.optBoolean("hasJoinCode", true),
    )

    private fun JSONObject.optStringOrNull(name: String) = if (!has(name) || isNull(name)) null else getString(name)

    private companion object {
        const val KEY_URL = "server_url"
        const val KEY_TOKEN = "token"
        const val KEY_HOUSEHOLD = "household_name"
        const val KEY_ADMIN = "admin_member_id"
        const val KEY_MEMBER = "member_id"
        const val KEY_REQUESTED = "requested_name"
        const val KEY_REQUESTS = "pending_requests"
        const val KEY_SKIPPED = "skipped_setup"
        const val KEY_HOUSEHOLD_ID = "household_id"
        const val KEY_NOTIFY = "notifications_on"
        const val KEY_HAS_JOIN_CODE = "has_join_code"
        const val KEY_OTHERS = "other_households"
    }
}
