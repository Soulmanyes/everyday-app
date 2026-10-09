package app.everyday.android.ui

import android.content.Context
import androidx.core.content.edit
import app.everyday.android.ui.calendar.MonthFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Display preferences kept on this phone only (not synced). */
class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private val _followPhoneTheme = MutableStateFlow(prefs.getBoolean(KEY_FOLLOW, false))

    /** False (default): always dark. True: follow the phone's light/dark setting. */
    val followPhoneTheme: StateFlow<Boolean> = _followPhoneTheme.asStateFlow()

    fun setFollowPhoneTheme(follow: Boolean) {
        prefs.edit { putBoolean(KEY_FOLLOW, follow) }
        _followPhoneTheme.value = follow
    }

    private val _monthFilter = MutableStateFlow(
        MonthFilter(prefs.getBoolean(KEY_MONTH_EVERYONE, true), prefs.getStringSet(KEY_MONTH_MEMBERS, null).orEmpty().toSet()),
    )

    /** Whose chores and events the month view shows (Phase 7). */
    val monthFilter: StateFlow<MonthFilter> = _monthFilter.asStateFlow()

    fun setMonthFilter(filter: MonthFilter) {
        prefs.edit {
            putBoolean(KEY_MONTH_EVERYONE, filter.everyone)
            putStringSet(KEY_MONTH_MEMBERS, filter.memberIds)
        }
        _monthFilter.value = filter
    }

    private companion object {
        const val KEY_FOLLOW = "follow_phone_theme"
        const val KEY_MONTH_EVERYONE = "month_everyone"
        const val KEY_MONTH_MEMBERS = "month_members"
    }
}
