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
import app.everyday.android.data.ChoreCompletion
import app.everyday.android.data.ChoreRepository
import app.everyday.android.data.MemberEntity
import app.everyday.android.household.ApiError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

data class ChoreDraft(
    val title: String = "",
    val notes: String = "",
    val weekdays: Int = 0,
    val completion: ChoreCompletion = ChoreCompletion.ANY,
    val assigneeIds: List<String> = emptyList(),
)

/** Add or edit a chore. Saving needs the server; the id is made here so a retry doesn't create a duplicate. */
class ChoreEditorViewModel(
    private val choreId: String?,
    private val manager: ChoreManager,
    repository: ChoreRepository,
    members: Flow<List<MemberEntity>>,
) : ViewModel() {
    private val id = choreId ?: UUID.randomUUID().toString()
    val isNew get() = choreId == null
    val members = members.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var draft by mutableStateOf(ChoreDraft())
        private set
    var loaded by mutableStateOf(choreId == null)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    init {
        if (choreId != null) viewModelScope.launch {
            repository.observeChores().first().firstOrNull { it.chore.id == choreId }?.let { c ->
                draft = ChoreDraft(c.chore.title, c.chore.notes.orEmpty(), c.chore.weekdays, c.chore.completion, c.assigneeIds)
            }
            loaded = true
        }
    }

    fun update(transform: (ChoreDraft) -> ChoreDraft) {
        draft = transform(draft)
        error = null
    }

    fun toggleDay(bit: Int) = update { it.copy(weekdays = it.weekdays xor (1 shl bit)) }

    fun toggleAssignee(memberId: String) = update {
        it.copy(assigneeIds = if (memberId in it.assigneeIds) it.assigneeIds - memberId else it.assigneeIds + memberId)
    }

    fun save(onSaved: () -> Unit) {
        val d = draft
        // Members removed since the chore was loaded can't be sent back.
        val known = members.value.map { it.id }.toSet()
        val assignees = d.assigneeIds.filter { it in known }
        error = when {
            d.title.isBlank() -> "Enter a title."
            d.weekdays == 0 -> "Choose at least one day."
            assignees.isEmpty() -> "Assign at least one person."
            else -> null
        }
        if (error != null) return
        run({ manager.save(id, d.title.trim(), d.notes.trim().ifEmpty { null }, d.weekdays, d.completion, assignees) }, onSaved)
    }

    fun delete(onDeleted: () -> Unit) {
        val existing = choreId ?: return
        run({ manager.delete(existing) }, onDeleted)
    }

    private fun run(action: suspend () -> Unit, onSuccess: () -> Unit) {
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

    companion object {
        fun factory(choreId: String?) = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as EverydayApplication
                ChoreEditorViewModel(choreId, app.chores, app.choreRepository, app.repository.observeMembers())
            }
        }
    }
}
