package app.everyday.android.ui.shared

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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Reminders other members shared with the current profile, to opt in or out. */
class SharedViewModel(
    private val repository: ReminderRepository,
    private val engine: ReminderEngine,
    private val requestSync: () -> Unit,
) : ViewModel() {
    val shared = repository.observeSharedWithMe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val members = repository.observeMembers().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun answer(reminderId: String, status: RecipientStatus) = viewModelScope.launch {
        repository.setMyAnswer(reminderId, status)
        engine.recompute()
        requestSync()
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as EverydayApplication
                SharedViewModel(app.repository, app.engine, app.sync::requestSync)
            }
        }
    }
}
