package app.everyday.android.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.everyday.android.EverydayApplication
import app.everyday.android.data.ReminderRepository
import app.everyday.android.reminders.ReminderEngine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CalendarViewModel(
    private val repository: ReminderRepository,
    private val engine: ReminderEngine,
) : ViewModel() {
    val reminders = repository.observeReminders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setActive(id: String, active: Boolean) = viewModelScope.launch {
        repository.setActive(id, active)
        engine.recompute()
    }

    fun setDoneToday(id: String, done: Boolean) = viewModelScope.launch {
        engine.setDoneToday(id, done)
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as EverydayApplication
                CalendarViewModel(app.repository, app.engine)
            }
        }
    }
}
