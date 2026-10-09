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

    private val _monthFilter = MutableStateFlow(readMonthFilter())

    /** Events and chores per member; a filter saved before they were split covers both for its members. */
    private fun readMonthFilter(): MonthFilter {
        val old = prefs.getStringSet(KEY_MONTH_MEMBERS, null).orEmpty().toSet()
        return MonthFilter(
            everyone = prefs.getBoolean(KEY_MONTH_EVERYONE, true),
            eventMemberIds = prefs.getStringSet(KEY_MONTH_EVENT_MEMBERS, null)?.toSet() ?: old,
            choreMemberIds = prefs.getStringSet(KEY_MONTH_CHORE_MEMBERS, null)?.toSet() ?: old,
        )
    }

    private val _monthMode = MutableStateFlow(prefs.getBoolean(KEY_MONTH_MODE, false))

    /** Whether the Events tab shows the month (true) or the week; kept across tabs and restarts. */
    val monthMode: StateFlow<Boolean> = _monthMode.asStateFlow()

    fun setMonthMode(month: Boolean) {
        prefs.edit { putBoolean(KEY_MONTH_MODE, month) }
        _monthMode.value = month
    }

    /** Whose chores and events the month view shows (Phase 7). */
    val monthFilter: StateFlow<MonthFilter> = _monthFilter.asStateFlow()

    fun setMonthFilter(filter: MonthFilter) {
        prefs.edit {
            putBoolean(KEY_MONTH_EVERYONE, filter.everyone)
            putStringSet(KEY_MONTH_EVENT_MEMBERS, filter.eventMemberIds)
            putStringSet(KEY_MONTH_CHORE_MEMBERS, filter.choreMemberIds)
            remove(KEY_MONTH_MEMBERS)
        }
        _monthFilter.value = filter
    }

    private companion object {
        const val KEY_FOLLOW = "follow_phone_theme"
        const val KEY_MONTH_MODE = "month_mode"
        const val KEY_MONTH_EVERYONE = "month_everyone"
        /** Before events and chores were split: both, for these members. */
        const val KEY_MONTH_MEMBERS = "month_members"
        const val KEY_MONTH_EVENT_MEMBERS = "month_event_members"
        const val KEY_MONTH_CHORE_MEMBERS = "month_chore_members"
    }
}
