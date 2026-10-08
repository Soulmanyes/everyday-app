package app.everyday.android.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.everyday.android.EverydayApplication
import app.everyday.android.data.RecipientStatus
import app.everyday.android.data.ReminderRepository
import app.everyday.android.reminders.ReminderEngine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CalendarViewModel(
    private val repository: ReminderRepository,
    private val engine: ReminderEngine,
    private val requestSync: () -> Unit,
) : ViewModel() {
    val reminders = repository.observeReminders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val pendingInvites = repository.observeSharedWithMe()
        .map { list -> list.count { it.myAnswer == RecipientStatus.PENDING } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val members = repository.observeMembers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setActive(id: String, active: Boolean) = viewModelScope.launch {
        repository.setActive(id, active)
        engine.recompute()
        requestSync()
    }

    fun setDoneToday(id: String, done: Boolean) = viewModelScope.launch {
        engine.setDoneToday(id, done)
        requestSync()
    }

    /** Opts the current profile in or out of a reminder someone shared with them. */
    fun setAnswer(id: String, status: RecipientStatus) = viewModelScope.launch {
        repository.setMyAnswer(id, status)
        engine.recompute()
        requestSync()
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as EverydayApplication
                CalendarViewModel(app.repository, app.engine, app.sync::requestSync)
            }
        }
    }
}
