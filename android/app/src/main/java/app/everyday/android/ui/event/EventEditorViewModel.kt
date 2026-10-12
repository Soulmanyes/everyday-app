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
import app.everyday.android.data.Sharing
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

    /** A multi-day weekly event starts on one weekday: picking another makes it a one-day event again. */
    fun toggleDay(bit: Int) = update {
        val weekdays = it.weekdays xor (1 shl bit)
        it.copy(weekdays = weekdays, endDays = if (Integer.bitCount(weekdays) == 1) it.endDays else 0)
    }

    /** Adds a member to the event or shares it with them for scheduling ([role]); a person is in one list at most. */
    fun toggleMember(memberId: String, role: String) = update {
        val taking = role == Sharing.MEMBER
        val list = if (taking) it.memberIds else it.schedulingIds
        val picked = memberId !in list
        val updated = if (picked) list + memberId else list - memberId
        if (taking) {
            it.copy(memberIds = updated, schedulingIds = if (picked) it.schedulingIds - memberId else it.schedulingIds)
        } else {
            it.copy(schedulingIds = updated, memberIds = if (picked) it.memberIds - memberId else it.memberIds)
        }
    }

    /** Everyone in [role], or nobody by default. Members picked in the other list keep their way. */
    fun toggleEveryone(role: String) = update { it.copy(everyone = if (it.everyone == role) Sharing.NONE else role) }

    fun setAllDay(allDay: Boolean) = update {
        if (allDay) it.copy(startMinute = null, endMinute = null) else it.copy(startMinute = nextHour(), endMinute = null)
    }

    /** Multi-day (Phase 9.6): ends [days] after the start day; 0 = the same day. */
    fun setEndDays(days: Int) = update { it.copy(endDays = days, endMinute = it.endMinute.takeIf { _ -> it.startMinute != null }) }

    fun save(onSaved: () -> Unit) {
        val d = draft
        error = when {
            d.title.isBlank() -> "Enter a title."
            d.weekly && d.weekdays == 0 -> "Choose at least one day."
            !d.weekly && d.date == null -> "Choose a date."
            d.endDays > 0 && d.weekly && Integer.bitCount(d.weekdays) != 1 -> "An event over several days starts on one day of the week."
            d.endDays > (if (d.weekly) 6 else MAX_END_DAYS) -> "An event can last up to ${if (d.weekly) "a week" else "$MAX_END_DAYS days"}."
            d.endDays > 0 && d.startMinute != null && d.endMinute == null -> "Choose when it ends."
            d.endDays == 0 && d.startMinute != null && d.endMinute != null && d.endMinute <= d.startMinute -> "The end time must be after the start time."
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
        /** A one-off event lasts up to this many days after its start day (Phase 9.6). */
        const val MAX_END_DAYS = 31

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
