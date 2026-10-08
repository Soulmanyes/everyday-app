package app.everyday.android.ui.chores

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.everyday.android.EverydayApplication
import app.everyday.android.chores.ChoreManager
import app.everyday.android.data.ChoreRepository
import app.everyday.android.data.MemberEntity
import app.everyday.android.household.ApiError
import app.everyday.android.household.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The chore timetable: cached chores, plus server actions with one error shown at a time. */
class ChoresViewModel(
    private val manager: ChoreManager,
    repository: ChoreRepository,
    session: Session,
    members: Flow<List<MemberEntity>>,
) : ViewModel() {
    val chores = repository.observeChores().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val members = members.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val session = session.state

    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    init {
        // Opening the Chores tab fetches the latest timetable.
        viewModelScope.launch { manager.refresh() }
    }

    fun markDone(id: String) = run { manager.markDone(id) }

    fun undoDone(id: String) = run { manager.undoDone(id) }

    private fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        viewModelScope.launch {
            try {
                action()
            } catch (e: ApiError) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as EverydayApplication
                ChoresViewModel(app.chores, app.choreRepository, app.session, app.repository.observeMembers())
            }
        }
    }
}
