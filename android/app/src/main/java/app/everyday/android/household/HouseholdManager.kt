package app.everyday.android.household

import android.content.Context
import android.os.Build
import android.provider.Settings
import app.everyday.android.data.ReminderRepository
import app.everyday.android.reminders.ReminderEngine
import java.time.ZoneId

/**
 * Household membership on this phone: creating or joining the household, picking a profile,
 * keeping the member list fresh, and the admin's member management.
 */
class HouseholdManager(
    private val context: Context,
    private val session: Session,
    private val repository: ReminderRepository,
    private val engine: ReminderEngine,
) {
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
        saveJoin(serverUrl, result)
        chooseProfile(result.household.adminMemberId ?: result.household.members.first().id)
    }

    /** Joins with the household code. The user picks their profile next. */
    suspend fun join(serverUrl: String, householdCode: String) {
        saveJoin(serverUrl, ApiClient(serverUrl).join(householdCode, deviceLabel()))
    }

    /**
     * Picks whose phone this is. The first pick after joining takes over the reminders made before
     * joining; later switches leave each profile's reminders with that profile.
     */
    suspend fun chooseProfile(memberId: String) {
        if (session.current.memberId == null) repository.adoptLocalProfile(memberId)
        session.update { it.copy(memberId = memberId) }
        engine.recompute()
    }

    /** Lets the user switch profile: back to the picker. */
    suspend fun clearProfile() {
        session.update { it.copy(memberId = null) }
        engine.recompute()
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

    private suspend fun saveJoin(serverUrl: String, result: JoinResult) {
        session.update {
            it.copy(
                serverUrl = serverUrl,
                token = result.token,
                householdName = result.household.name,
                adminMemberId = result.household.adminMemberId,
                memberId = null,
                skippedSetup = false,
            )
        }
        apply(result.household)
    }

    private suspend fun apply(household: HouseholdInfo) {
        repository.cacheMembers(household.members.map { it.id to it.displayName })
        session.update { state ->
            state.copy(
                householdName = household.name,
                adminMemberId = household.adminMemberId,
                // A profile removed by the admin sends this phone back to the picker.
                memberId = state.memberId?.takeIf { id -> household.members.any { it.id == id } },
            )
        }
    }

    private fun deviceLabel(): String =
        Settings.Global.getString(context.contentResolver, "device_name")?.takeIf { it.isNotBlank() } ?: Build.MODEL
}
