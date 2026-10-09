package app.everyday.android.household

import android.content.Context
import android.os.Build
import android.provider.Settings
import app.everyday.android.data.ReminderRepository
import androidx.core.content.edit
import app.everyday.android.reminders.Notifier
import app.everyday.android.reminders.ReminderEngine
import java.time.ZoneId

/**
 * Household membership on this phone: creating the household, asking to join and waiting for the
 * admin's approval, keeping the member list fresh, and the admin's member management and approvals.
 */
class HouseholdManager(
    private val context: Context,
    private val session: Session,
    private val repository: ReminderRepository,
    private val engine: ReminderEngine,
) {
    private val notifier = Notifier(context)

    private fun client(baseUrl: String = session.current.serverUrl) = ApiClient(baseUrl, session.current.token)

    suspend fun householdExists(serverUrl: String) = ApiClient(serverUrl).householdExists()

    /** Creates the household. The creator becomes the admin and their profile is picked automatically. */
    suspend fun create(serverUrl: String, setupCode: String, householdName: String, householdCode: String, adminName: String, adminPin: String) {
        val result = ApiClient(serverUrl).createHousehold(
            setupToken = setupCode,
            householdName = householdName,
            householdCode = householdCode,
            timezone = ZoneId.systemDefault().id,
            adminName = adminName,
            adminPin = adminPin,
            deviceLabel = deviceLabel(),
        )
        saveJoin(serverUrl, result, requestedName = adminName)
    }

    /**
     * Asks to join as [name]; the phone waits for the admin's approval (see [refresh]). With [adminPin],
     * the admin's own new phone is approved as the admin at once.
     */
    suspend fun join(serverUrl: String, householdCode: String, name: String, adminPin: String? = null) {
        saveJoin(serverUrl, ApiClient(serverUrl).join(householdCode, name, deviceLabel(), adminPin), requestedName = name)
    }

    /**
     * Refreshes the household and member list. If the server no longer accepts this phone's token,
     * the session is cleared and the app returns to the welcome screen. Network errors are ignored.
     */
    suspend fun refresh() {
        if (!session.current.joined) return
        try {
            apply(client().me())
        } catch (e: ApiError) {
            if (e.isUnauthorized) {
                session.clear()
                engine.recompute()
            }
        }
    }

    /** Leaves the household on this phone. The profile's reminders stay stored but hidden until it rejoins. */
    suspend fun leave() {
        runCatching { client().leave() }
        session.clear()
        engine.recompute()
    }

    fun skipSetup() = session.update { it.copy(skippedSetup = true) }

    fun startSetup() = session.update { it.copy(skippedSetup = false) }

    // Admin actions. Each sends the PIN, which the server checks.

    suspend fun verifyAdminPin(pin: String) = client().verifyAdminPin(pin)

    suspend fun addMember(pin: String, name: String) {
        client().addMember(pin, name)
        refresh()
    }

    suspend fun renameMember(pin: String, id: String, name: String) {
        client().renameMember(pin, id, name)
        refresh()
    }

    suspend fun removeMember(pin: String, id: String) {
        client().removeMember(pin, id)
        refresh()
    }

    private suspend fun saveJoin(serverUrl: String, result: JoinResult, requestedName: String) {
        session.update {
            it.copy(
                serverUrl = serverUrl,
                token = result.token,
                householdName = result.household.name,
                adminMemberId = result.household.adminMemberId,
                memberId = null,
                requestedName = requestedName,
                skippedSetup = false,
            )
        }
        apply(result.household)
    }

    private suspend fun apply(household: HouseholdInfo) {
        repository.cacheMembers(household.members.map { it.id to it.displayName })
        val memberId = household.memberId?.takeIf { household.approved }
        // Approved just now: reminders made on this phone before joining become this member's.
        val newlyApproved = memberId != null && session.current.memberId != memberId
        if (newlyApproved) repository.adoptLocalProfile(memberId!!)
        session.update { state ->
            state.copy(
                householdName = household.name,
                adminMemberId = household.adminMemberId,
                memberId = memberId,
                pendingRequests = household.pendingRequestIds.size,
            )
        }
        if (newlyApproved) engine.recompute()
        announceRequests(household.pendingRequestIds)
    }

    /** On the admin's phone: one notification per new join request. */
    private fun announceRequests(ids: List<String>) {
        val prefs = context.getSharedPreferences("household", Context.MODE_PRIVATE)
        val announced = prefs.getStringSet("announced_requests", emptySet())!!
        val fresh = ids.filter { it !in announced }
        if (fresh.isNotEmpty()) notifier.showJoinRequests(ids.size, session.current.householdName.orEmpty())
        if (ids.isEmpty()) notifier.cancelJoinRequests()
        prefs.edit { putStringSet("announced_requests", ids.toSet()) }
    }

    // Join requests (admin, with the PIN).

    suspend fun joinRequests(pin: String) = client().joinRequests(pin)

    suspend fun approveRequest(pin: String, id: String, memberId: String?) {
        client().approveRequest(pin, id, memberId)
        refresh()
    }

    suspend fun rejectRequest(pin: String, id: String) {
        client().rejectRequest(pin, id)
        refresh()
    }

    private fun deviceLabel(): String =
        Settings.Global.getString(context.contentResolver, "device_name")?.takeIf { it.isNotBlank() } ?: Build.MODEL
}
