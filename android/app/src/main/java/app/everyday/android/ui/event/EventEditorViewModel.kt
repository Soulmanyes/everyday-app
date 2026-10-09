package app.everyday.android.ui.event

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.everyday.android.EverydayApplication
import app.everyday.android.data.EventDraft
import app.everyday.android.data.MemberEntity
import app.everyday.android.data.ReminderRepository
import app.everyday.android.reminders.MINUTES_PER_DAY
import app.everyday.android.reminders.ReminderEngine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

class EventEditorViewModel(
    private val eventId: String?,
    private val repository: ReminderRepository,
    private val engine: ReminderEngine,
    private val requestSync: () -> Unit,
    /** Household members other than the owner, who the event can be shared with. */
    val members: StateFlow<List<MemberEntity>>,
) : ViewModel() {
    var draft by mutableStateOf(newDraft())
        private set
    var loaded by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    val isNew get() = eventId == null

    init {
        viewModelScope.launch {
            if (eventId != null) repository.getEventDraft(eventId)?.let { draft = it }
            loaded = true
        }
    }

    fun update(transform: (EventDraft) -> EventDraft) {
        draft = transform(draft)
        error = null
    }

    fun toggleDay(bit: Int) = update { it.copy(weekdays = it.weekdays xor (1 shl bit)) }

    fun toggleMember(memberId: String) = update {
        it.copy(memberIds = if (memberId in it.memberIds) it.memberIds - memberId else it.memberIds + memberId)
    }

    fun setAllDay(allDay: Boolean) = update {
        if (allDay) it.copy(startMinute = null, endMinute = null) else it.copy(startMinute = nextHour(), endMinute = null)
    }

    fun save(onSaved: () -> Unit) {
        val d = draft
        error = when {
            d.title.isBlank() -> "Enter a title."
            d.weekly && d.weekdays == 0 -> "Choose at least one day."
            !d.weekly && d.date == null -> "Choose a date."
            d.startMinute != null && d.endMinute != null && d.endMinute <= d.startMinute -> "The end time must be after the start time."
            else -> null
        }
        if (error != null) return
        viewModelScope.launch {
            repository.saveEvent(d)
            engine.recompute()
            requestSync()
            onSaved()
        }
    }

    private fun newDraft(): EventDraft {
        val today = LocalDate.now()
        val start = nextHour()
        return EventDraft(weekdays = 1 shl (today.dayOfWeek.value - 1), date = today, startMinute = start, endMinute = minOf(start + 60, MINUTES_PER_DAY))
    }

    private fun nextHour() = minOf(LocalTime.now().hour + 1, 23) * 60

    companion object {
        fun factory(eventId: String?) = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as EverydayApplication
                EventEditorViewModel(
                    eventId,
                    app.repository,
                    app.engine,
                    app.sync::requestSync,
                    app.repository.observeMembers()
                        .map { list -> list.filter { it.id != app.session.current.memberId } }
                        .stateIn(app.mainScope, SharingStarted.WhileSubscribed(5_000), emptyList()),
                )
            }
        }
    }
}
