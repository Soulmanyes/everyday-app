package app.everyday.android.household

import android.content.Context
import android.os.Build
import android.provider.Settings
import app.everyday.android.data.MemberEntity
import app.everyday.android.data.ReminderRepository
import androidx.core.content.edit
import app.everyday.android.reminders.Notifier
import app.everyday.android.reminders.ReminderEngine
import java.time.ZoneId

/**
 * Household membership on this phone: creating a household, asking to join and waiting for the admin's
 * approval, keeping every household's member list fresh, and the admin's member management, approvals,
 * join codes and invites. A phone can be in several households (Phase 9); actions from the screens act
 * on the picked one.
 */
class HouseholdManager(
    private val context: Context,
    private val session: Session,
    private val repository: ReminderRepository,
    private val engine: ReminderEngine,
) {
    private val notifier = Notifier(context)

    private fun client() = ApiClient(session.current.serverUrl, session.current.token)

    /**
     * Creates a household with an invite from another household's admin (or the server's setup code). The
     * creator becomes its admin, and it becomes the picked household. Returns its join code, shown only once.
     */
    suspend fun create(serverUrl: String, inviteCode: String, householdName: String, adminName: String, adminPin: String): String? {
        val result = ApiClient(serverUrl).createHousehold(
            inviteCode = inviteCode,
            householdName = householdName,
            timezone = ZoneId.systemDefault().id,
            adminName = adminName,
            adminPin = adminPin,
            deviceLabel = deviceLabel(),
        )
        saveJoin(serverUrl, result, requestedName = adminName)
        return result.joinCode
    }

    /**
     * Asks to join as [name] with the household's join code; the phone waits for the admin's approval (see
     * [refresh]). With [adminPin], the admin's own new phone is approved as the admin at once.
     */
    suspend fun join(serverUrl: String, joinCode: String, name: String, adminPin: String? = null) {
        saveJoin(serverUrl, ApiClient(serverUrl).join(joinCode, name, deviceLabel(), adminPin), requestedName = name)
    }

    /**
     * Refreshes every household this phone is in, with its member list. A household whose server no longer
     * accepts this phone's token is forgotten; with none left the app returns to the welcome screen.
     * Network errors are ignored.
     */
    suspend fun refresh() {
        for (m in session.current.memberships) {
            try {
                apply(m, ApiClient(m.serverUrl, m.token).me())
            } catch (e: ApiError) {
                if (e.isUnauthorized) forget(m.token)
            }
        }
    }

    /** The server no longer accepts [token] (removed, rejected, household deleted). */
    suspend fun forget(token: String) {
        session.remove(token)
        engine.recompute()
    }

    /** Leaves the picked household on this phone. The profile's reminders stay stored but hidden until it rejoins. */
    suspend fun leave() {
        val token = session.current.token ?: return
        runCatching { client().leave() }
        forget(token)
    }

    /** Shows another household this phone is in. */
    fun pick(token: String) = session.pick(token)

    /** Turns one household's reminders and notifications on or off on this phone. */
    suspend fun setNotifications(token: String, on: Boolean) {
        session.updateMembership(token) { it.copy(notificationsOn = on) }
        engine.recompute()
    }

    fun skipSetup() = session.update { it.copy(skippedSetup = true) }

    fun startSetup() = session.update { it.copy(skippedSetup = false) }

    // Admin actions on the picked household. Each sends the PIN, which the server checks.

    suspend fun verifyAdminPin(pin: String) = client().verifyAdminPin(pin)

    /** A new join code for the picked household; the old one stops working. Shown only once. */
    suspend fun newJoinCode(pin: String): String = client().newJoinCode(pin).also { refresh() }

    suspend fun invites(pin: String) = client().invites(pin)

    /** A new invite for a friend to create their own household. Shown only once. */
    suspend fun createInvite(pin: String): String = client().createInvite(pin)

    suspend fun cancelInvite(pin: String, id: String) = client().cancelInvite(pin, id)

    /** Deletes the picked household and everything in it, for every member. */
    suspend fun deleteHousehold(pin: String) {
        val token = session.current.token ?: return
        client().deleteHousehold(pin)
        forget(token)
    }

    suspend fun addMember(pin: String, name: String) {
        client().addMember(pin, name)
        refresh()
    }

    suspend fun renameMember(pin: String, id: String, name: String) {
        client().renameMember(pin, id, name)
        refresh()
    }

    /** The admin sets a member's colour (with the PIN). */
    suspend fun setMemberColor(pin: String, id: String, name: String, color: String) {
        client().renameMember(pin, id, name, color)
        refresh()
    }

    /** The current member picks their own colour; throws if someone else has it. */
    suspend fun setMyColor(color: String) {
        client().setMyColor(color)
        refresh()
    }

    suspend fun removeMember(pin: String, id: String) {
        client().removeMember(pin, id)
        refresh()
    }

    /** A newly created or joined household becomes the picked one; households joined before stay. */
    private suspend fun saveJoin(serverUrl: String, result: JoinResult, requestedName: String) {
        // Joining a household this phone is already in (a second time) replaces the old membership.
        session.current.memberships.filter { it.householdId == result.household.id }.forEach { session.remove(it.token) }
        val membership = Membership(
            serverUrl = serverUrl,
            token = result.token,
            householdId = result.household.id,
            householdName = result.household.name,
            adminMemberId = result.household.adminMemberId,
            requestedName = requestedName,
        )
        session.add(membership)
        apply(membership, result.household)
    }

    private suspend fun apply(m: Membership, household: HouseholdInfo) {
        repository.cacheMembers(household.id, household.members.map { MemberEntity(it.id, it.displayName, 0, color = it.color) })
        val memberId = household.memberId?.takeIf { household.approved }
        // Approved just now: reminders made on this phone before joining become this member's.
        val newlyApproved = memberId != null && m.memberId != memberId
        if (newlyApproved) repository.adoptLocalProfile(memberId!!)
        session.updateMembership(m.token) {
            it.copy(
                householdId = household.id,
                householdName = household.name,
                adminMemberId = household.adminMemberId,
                memberId = memberId,
                pendingRequests = household.pendingRequestIds.size,
                hasJoinCode = household.hasJoinCode,
            )
        }
        if (newlyApproved) engine.recompute()
        announceRequests(household.id, household.name, household.pendingRequestIds)
    }

    /** On an admin's phone: one notification per household with new join requests. */
    private fun announceRequests(householdId: String, householdName: String, ids: List<String>) {
        val prefs = context.getSharedPreferences("household", Context.MODE_PRIVATE)
        // Before 2.0 the phone was in one household, whose announced requests had no household in the key.
        val key = "announced_requests_$householdId"
        val announced = prefs.getStringSet(key, null) ?: prefs.getStringSet("announced_requests", emptySet())!!
        val fresh = ids.filter { it !in announced }
        if (fresh.isNotEmpty()) notifier.showJoinRequests(ids.size, householdName, householdId)
        if (ids.isEmpty()) notifier.cancelJoinRequests(householdId)
        prefs.edit { putStringSet(key, ids.toSet()) }
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
