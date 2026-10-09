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
import app.everyday.android.data.ChoreEntity
import app.everyday.android.data.ChoreSlotEntity
import app.everyday.android.data.MemberEntity
import app.everyday.android.data.ReminderMode
import app.everyday.android.data.ReminderRepository
import app.everyday.android.reminders.ReminderEngine
import kotlinx.coroutines.flow.map
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
    private val reminders: ReminderRepository,
    private val engine: ReminderEngine,
    private val requestSync: () -> Unit,
) : ViewModel() {
    /**
     * The current profile's own slots per chore, from its chore reminders on this phone: shown at once,
     * where other members' slots come from the server.
     */
    val mySlots = reminders.observeReminders().map { list ->
        list.filter { it.reminder.choreId != null && it.reminder.active }.flatMap { r ->
            val choreId = r.reminder.choreId!!
            when (r.reminder.mode) {
                ReminderMode.TIMED -> r.times.map { ChoreSlotEntity(choreId, r.reminder.ownerMemberId, it.minuteOfDay) }
                ReminderMode.PERSISTENT -> r.windows.map { ChoreSlotEntity(choreId, r.reminder.ownerMemberId, it.startMinute, it.endMinute) }
            }
        }.distinctBy { Triple(it.choreId, it.startMinute, it.endMinute) }.sortedBy { it.startMinute }.groupBy { it.choreId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Quick time on a chore the current profile is assigned to: a timed chore reminder. */
    fun addTime(chore: ChoreEntity, minute: Int) = viewModelScope.launch {
        reminders.addChoreTime(chore.id, chore.title, chore.weekdays, minute)
        engine.recompute()
        requestSync()
    }

    fun removeTime(choreId: String, minute: Int) = viewModelScope.launch {
        reminders.removeChoreTime(choreId, minute)
        engine.recompute()
        requestSync()
    }

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
                ChoresViewModel(
                    app.chores,
                    app.choreRepository,
                    app.session,
                    app.repository.observeMembers(),
                    app.repository,
                    app.engine,
                    app.sync::requestSync,
                )
            }
        }
    }
}
