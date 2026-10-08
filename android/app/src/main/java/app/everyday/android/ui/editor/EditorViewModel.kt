package app.everyday.android.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.everyday.android.EverydayApplication
import app.everyday.android.data.ChoreRepository
import app.everyday.android.data.ReminderDraft
import app.everyday.android.data.ReminderMode
import app.everyday.android.data.ReminderRepository
import app.everyday.android.reminders.MINUTES_PER_DAY
import app.everyday.android.reminders.ReminderEngine
import app.everyday.android.reminders.Window
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

val ALL_DAY = Window(0, MINUTES_PER_DAY)

class EditorViewModel(
    private val reminderId: String?,
    /** For a new chore reminder: the chore it belongs to. */
    newChoreId: String?,
    private val repository: ReminderRepository,
    private val chores: ChoreRepository,
    private val engine: ReminderEngine,
) : ViewModel() {
    var draft by mutableStateOf(ReminderDraft())
        private set
    var loaded by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    val isNew get() = reminderId == null

    /** Title of the chore this reminder belongs to, if any. */
    var choreTitle by mutableStateOf<String?>(null)
        private set

    init {
        viewModelScope.launch {
            if (reminderId != null) {
                repository.getDraft(reminderId)?.let { draft = it }
            } else if (newChoreId != null) {
                // A new chore reminder starts with the chore's title and days.
                chore(newChoreId)?.let { c ->
                    draft = ReminderDraft(message = c.chore.title, weekdays = c.chore.weekdays, choreId = c.chore.id)
                }
            }
            choreTitle = draft.choreId?.let { chore(it)?.chore?.title }
            loaded = true
        }
    }

    private suspend fun chore(id: String) = chores.observeChores().first().firstOrNull { it.chore.id == id }

    fun update(transform: (ReminderDraft) -> ReminderDraft) {
        draft = transform(draft)
        error = null
    }

    fun toggleDay(bit: Int) = update { it.copy(weekdays = it.weekdays xor (1 shl bit)) }

    fun addTime(minute: Int) = update { it.copy(times = (it.times + minute).distinct().sorted()) }

    fun removeTime(minute: Int) = update { it.copy(times = it.times - minute) }

    fun setAllDay(allDay: Boolean) = update { it.copy(windows = if (allDay) listOf(ALL_DAY) else emptyList()) }

    /** Adds a window, or explains why it can't be added. */
    fun addWindow(window: Window): Boolean {
        val problem = when {
            window.endMinute <= window.startMinute -> "The end time must be after the start time."
            draft.windows.any { window.startMinute < it.endMinute && it.startMinute < window.endMinute } ->
                "That overlaps another time window."
            else -> null
        }
        if (problem != null) {
            error = problem
            return false
        }
        update { it.copy(windows = (it.windows + window).sortedBy { w -> w.startMinute }) }
        return true
    }

    fun removeWindow(window: Window) = update { it.copy(windows = it.windows - window) }

    fun save(onSaved: () -> Unit) {
        val d = draft
        error = when {
            d.message.isBlank() -> "Enter a message."
            d.weekdays == 0 -> "Choose at least one day."
            d.mode == ReminderMode.TIMED && d.times.isEmpty() -> "Add at least one time."
            d.mode == ReminderMode.PERSISTENT && d.windows.isEmpty() -> "Add at least one time window, or choose All day."
            else -> null
        }
        if (error != null) return
        viewModelScope.launch {
            repository.save(d)
            engine.recompute()
            onSaved()
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val id = reminderId ?: return
        viewModelScope.launch {
            repository.delete(id)
            engine.recompute()
            onDeleted()
        }
    }

    companion object {
        fun factory(reminderId: String?, newChoreId: String?) = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as EverydayApplication
                EditorViewModel(reminderId, newChoreId, app.repository, app.choreRepository, app.engine)
            }
        }
    }
}
