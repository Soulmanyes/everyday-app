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

    fun create(serverUrl: String, setupCode: String, householdName: String, householdCode: String, adminName: String, pin: String) =
        run({ manager.create(serverUrl.trim(), setupCode.trim(), householdName, householdCode, adminName, pin) })

    fun join(serverUrl: String, householdCode: String, name: String, adminPin: String? = null) =
        run({ manager.join(serverUrl.trim(), householdCode, name.trim(), adminPin) })

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

    fun skipSetup() = manager.skipSetup()

    fun startSetup() = manager.startSetup()

    fun refresh() = viewModelScope.launch { manager.refresh() }

    fun unlockAdmin(pin: String, onUnlocked: () -> Unit) = run({ manager.verifyAdminPin(pin); requests = manager.joinRequests(pin) }) {
        adminPin = pin
        onUnlocked()
    }

    /** Forgets the PIN and any error from member management, so it doesn't show up on other screens. */
    fun lockAdmin() {
        adminPin = null
        requests = emptyList()
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

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as EverydayApplication
                HouseholdViewModel(app.household, app.session, app.repository.observeMembers())
            }
        }
    }
}
