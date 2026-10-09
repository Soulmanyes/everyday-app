package app.everyday.android.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.everyday.android.EverydayApplication
import app.everyday.android.data.Persistent
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
    val events = repository.observeEvents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Chore reminders also show on this timetable, in the chore colours. */
    val choreReminders = repository.observeReminders()
        .map { list -> list.filter { it.reminder.choreId != null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val members = repository.observeMembers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Runs a change, then reschedules alarms and syncs. */
    private fun change(block: suspend () -> Unit) = viewModelScope.launch {
        block()
        engine.recompute()
        requestSync()
    }

    fun setActive(reminderId: String, active: Boolean) = change { repository.setActive(reminderId, active) }

    fun setDoneToday(reminderId: String, done: Boolean) = viewModelScope.launch {
        engine.setDoneToday(reminderId, done)
        requestSync()
    }

    fun addEventTime(eventId: String, minute: Int, relative: Boolean) = change { repository.addEventTime(eventId, minute, relative) }

    fun removeEventTime(eventId: String, minute: Int, relative: Boolean) = change { repository.removeEventTime(eventId, minute, relative) }

    fun setPersistent(eventId: String, persistent: Persistent) = change { repository.setPersistent(eventId, persistent) }

    fun setEventActive(eventId: String, active: Boolean) = change { repository.setEventActive(eventId, active) }

    fun hideEvent(eventId: String) = change { repository.setHidden(eventId, true) }

    fun deleteEvent(eventId: String) = change { repository.deleteEvent(eventId) }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as EverydayApplication
                CalendarViewModel(app.repository, app.engine, app.sync::requestSync)
            }
        }
    }
}
