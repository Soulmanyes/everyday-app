package app.everyday.android.ui.household

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.everyday.android.EverydayApplication
import app.everyday.android.data.MemberEntity
import app.everyday.android.household.ApiError
import app.everyday.android.household.HouseholdManager
import app.everyday.android.household.InviteInfo
import app.everyday.android.household.JoinRequestInfo
import app.everyday.android.household.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Shared state for the household screens: one action at a time, with its error shown in the UI. */
class HouseholdViewModel(
    private val manager: HouseholdManager,
    session: Session,
    members: Flow<List<MemberEntity>>,
) : ViewModel() {
    val session = session.state
    val members = members.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    /** The admin PIN, kept only while the member management screen is in use. */
    var adminPin by mutableStateOf<String?>(null)
        private set

    private fun run(action: suspend () -> Unit, onSuccess: () -> Unit = {}) {
        if (busy) return
        busy = true
        error = null
        viewModelScope.launch {
            try {
                action()
                onSuccess()
            } catch (e: ApiError) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    /** A join code to show once: right after creating a household, or after the admin makes a new one. */
    var shownJoinCode by mutableStateOf<String?>(null)

    fun create(serverUrl: String, inviteCode: String, householdName: String, adminName: String, pin: String) =
        run({ shownJoinCode = manager.create(serverUrl.trim(), inviteCode.trim(), householdName, adminName, pin); addedHousehold() })

    fun join(serverUrl: String, joinCode: String, name: String, adminPin: String? = null) =
        run({ manager.join(serverUrl.trim(), joinCode.trim(), name.trim(), adminPin); addedHousehold() })

    /** A new household is the picked one: no picker, and back to the app. */
    private fun addedHousehold() {
        addingHousehold = false
        pickerDone = true
        lockAdmin()
    }

    /** Join requests, loaded once the admin PIN is entered. */
    var requests by mutableStateOf<List<JoinRequestInfo>>(emptyList())
        private set

    fun loadRequests() = adminPin?.let { pin -> run({ requests = manager.joinRequests(pin) }) }

    /** Approves as a new member ([memberId] null) or as an existing member. */
    fun approve(id: String, memberId: String?) = adminPin?.let { pin ->
        run({ manager.approveRequest(pin, id, memberId); requests = manager.joinRequests(pin) })
    }

    fun reject(id: String) = adminPin?.let { pin ->
        run({ manager.rejectRequest(pin, id); requests = manager.joinRequests(pin) })
    }

    fun leave(onDone: () -> Unit) = run({ manager.leave() }, onDone)

    // Several households (Phase 9).

    /** The picker was used (or skipped) since the app started; it shows again on the next start. */
    var pickerDone by mutableStateOf(false)
        private set

    /** Joining or setting up another household from Settings or the picker. */
    var addingHousehold by mutableStateOf(false)

    fun showPicker() {
        pickerDone = false
    }

    fun pick(token: String) {
        manager.pick(token)
        lockAdmin()
        pickerDone = true
    }

    fun setNotifications(token: String, on: Boolean) = viewModelScope.launch { manager.setNotifications(token, on) }

    /** The code from a join link, until the join screen fills it in. */
    var linkedJoinCode by mutableStateOf<String?>(null)

    /** A join link was opened: show the join screen with its code (as another household if the phone has one). */
    fun openJoinLink(code: String) {
        linkedJoinCode = code
        error = null
        if (session.value.joined) addingHousehold = true
    }

    fun startAddingHousehold() {
        error = null
        addingHousehold = true
    }

    /** An invite code to show once, right after making it. */
    var shownInvite by mutableStateOf<String?>(null)

    var invites by mutableStateOf<List<InviteInfo>>(emptyList())
        private set
    var invitesLeft by mutableStateOf(0)
        private set

    private suspend fun loadInvites(pin: String) {
        val (list, left) = manager.invites(pin)
        invites = list
        invitesLeft = left
    }

    /** Open invite links into this household (Phase 9.5), loaded with the admin PIN. */
    var memberInvites by mutableStateOf<List<InviteInfo>>(emptyList())
        private set

    /** Makes an invite link with [pin] and hands its code to [onCode] (which opens the share sheet). */
    fun inviteSomeone(pin: String, onCode: (String) -> Unit) {
        var code: String? = null
        run({
            code = manager.createMemberInvite(pin)
            if (adminPin == pin) memberInvites = manager.memberInvites(pin)
        }) { code?.let(onCode) }
    }

    fun cancelMemberInvite(id: String) =
        adminPin?.let { pin -> run({ manager.cancelMemberInvite(pin, id); memberInvites = manager.memberInvites(pin) }) }

    fun newJoinCode() = adminPin?.let { pin -> run({ shownJoinCode = manager.newJoinCode(pin) }) }

    fun createInvite() = adminPin?.let { pin -> run({ shownInvite = manager.createInvite(pin); loadInvites(pin) }) }

    fun cancelInvite(id: String) = adminPin?.let { pin -> run({ manager.cancelInvite(pin, id); loadInvites(pin) }) }

    fun deleteHousehold(onDone: () -> Unit) = adminPin?.let { pin -> run({ manager.deleteHousehold(pin); lockAdmin() }, onDone) }

    fun skipSetup() = manager.skipSetup()

    fun startSetup() = manager.startSetup()

    fun refresh() = viewModelScope.launch { manager.refresh() }

    fun unlockAdmin(pin: String, onUnlocked: () -> Unit) = run({
        manager.verifyAdminPin(pin)
        requests = manager.joinRequests(pin)
        loadInvites(pin)
        memberInvites = manager.memberInvites(pin)
        callers = manager.callers()
    }) {
        adminPin = pin
        onUnlocked()
    }

    /** Forgets the PIN and any error from member management, so it doesn't show up on other screens. */
    fun lockAdmin() {
        adminPin = null
        requests = emptyList()
        invites = emptyList()
        memberInvites = emptyList()
        callers = emptySet()
        error = null
    }

    fun addMember(name: String) = adminPin?.let { pin -> run({ manager.addMember(pin, name.trim()) }) }

    fun renameMember(id: String, name: String) = adminPin?.let { pin -> run({ manager.renameMember(pin, id, name.trim()) }) }

    /** The admin sets a member's colour. */
    fun setMemberColor(id: String, name: String, color: String) =
        adminPin?.let { pin -> run({ manager.setMemberColor(pin, id, name, color) }) }

    /** The current member picks their own colour (no PIN). */
    fun setMyColor(color: String) = run({ manager.setMyColor(color) })

    fun removeMember(id: String) = adminPin?.let { pin -> run({ manager.removeMember(pin, id) }) }

    /** Members the admin lets ring others (Phase 12), loaded with the admin PIN. */
    var callers by mutableStateOf<Set<String>>(emptySet())
        private set

    fun setCanCall(id: String, name: String, canCall: Boolean) = adminPin?.let { pin ->
        run({ manager.setCanCall(pin, id, name, canCall); callers = manager.callers() })
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as EverydayApplication
                HouseholdViewModel(app.household, app.session, app.repository.observeMembers())
            }
        }
    }
}
