package app.everyday.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.everyday.android.EverydayApplication
import app.everyday.android.data.MemberSettingsEntity
import app.everyday.android.data.ReminderRepository
import app.everyday.android.reminders.ReminderEngine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repository: ReminderRepository,
    val engine: ReminderEngine,
) : ViewModel() {
    val settings = repository.observeSettings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun update(transform: (MemberSettingsEntity) -> MemberSettingsEntity) = viewModelScope.launch {
        repository.saveSettings(transform(repository.getSettings()))
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as EverydayApplication
                SettingsViewModel(app.repository, app.engine)
            }
        }
    }
}
