package app.everyday.android.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.edit
import app.everyday.android.BuildConfig
import app.everyday.android.ui.calendar.MonthFilter
import app.everyday.android.ui.whatsnew.ReleaseNote
import app.everyday.android.ui.whatsnew.pendingNotes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Display preferences kept on this phone only (not synced). */
class AppPrefs(private val context: Context) {
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private val _followPhoneTheme = MutableStateFlow(prefs.getBoolean(KEY_FOLLOW, false))

    /** False (default): always dark. True: follow the phone's light/dark setting. */
    val followPhoneTheme: StateFlow<Boolean> = _followPhoneTheme.asStateFlow()

    fun setFollowPhoneTheme(follow: Boolean) {
        prefs.edit { putBoolean(KEY_FOLLOW, follow) }
        _followPhoneTheme.value = follow
    }

    private val _monthFilter = MutableStateFlow(readMonthFilter())

    /**
     * Events and chores per member; a filter saved before they were split covers both for its members. Null
     * until the user picks one: the Events tab then shows the profile's own events and chores (Phase 9.7).
     */
    private fun readMonthFilter(): MonthFilter? {
        if (!prefs.contains(KEY_MONTH_EVERYONE)) return null
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

    /** Whose chores and events the week and month views show (Phase 7; the week too since 9.7). Null = mine. */
    val monthFilter: StateFlow<MonthFilter?> = _monthFilter.asStateFlow()

    private val _showChores = MutableStateFlow(prefs.getBoolean(KEY_SHOW_CHORES, true))

    /** Chores and chore reminders on the Events tab (Phase 9.7); they always show on the Chores and Reminders tabs. */
    val showChores: StateFlow<Boolean> = _showChores.asStateFlow()

    private val _allChores = MutableStateFlow(prefs.getBoolean(KEY_ALL_CHORES, false))

    /** The Chores tab's Mine | All switch (Phase 9.9): false shows only the chores I'm assigned to or created. */
    val allChores: StateFlow<Boolean> = _allChores.asStateFlow()

    fun setAllChores(all: Boolean) {
        prefs.edit { putBoolean(KEY_ALL_CHORES, all) }
        _allChores.value = all
    }

    fun setShowChores(show: Boolean) {
        prefs.edit { putBoolean(KEY_SHOW_CHORES, show) }
        _showChores.value = show
    }

    fun setMonthFilter(filter: MonthFilter) {
        prefs.edit {
            putBoolean(KEY_MONTH_EVERYONE, filter.everyone)
            putStringSet(KEY_MONTH_EVENT_MEMBERS, filter.eventMemberIds)
            putStringSet(KEY_MONTH_CHORE_MEMBERS, filter.choreMemberIds)
            remove(KEY_MONTH_MEMBERS)
        }
        _monthFilter.value = filter
    }

    private val _whatsNew = MutableStateFlow(readWhatsNew())

    /** Release notes to show once after an update (Phase 8); empty once dismissed. */
    val whatsNew: StateFlow<List<ReleaseNote>> = _whatsNew.asStateFlow()

    fun dismissWhatsNew() {
        prefs.edit { putInt(KEY_NOTES_SEEN, BuildConfig.VERSION_CODE) }
        _whatsNew.value = emptyList()
    }

    /** Saved only when dismissed, so the popup waits until the main screen shows it. */
    private fun readWhatsNew(): List<ReleaseNote> {
        val lastSeen = if (prefs.contains(KEY_NOTES_SEEN)) prefs.getInt(KEY_NOTES_SEEN, 0) else null
        val notes = pendingNotes(lastSeen, BuildConfig.VERSION_CODE, freshInstall = isFreshInstall())
        // Nothing to show: remember this version, so a later update doesn't take this phone for one from before the popup.
        if (notes.isEmpty() && (lastSeen == null || lastSeen < BuildConfig.VERSION_CODE)) {
            prefs.edit { putInt(KEY_NOTES_SEEN, BuildConfig.VERSION_CODE) }
        }
        return notes
    }

    private fun isFreshInstall(): Boolean = runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") context.packageManager.getPackageInfo(context.packageName, 0)
        }
        info.firstInstallTime == info.lastUpdateTime
    }.getOrDefault(true)

    private companion object {
        const val KEY_FOLLOW = "follow_phone_theme"
        const val KEY_MONTH_MODE = "month_mode"
        const val KEY_SHOW_CHORES = "show_chores_on_events"
        const val KEY_ALL_CHORES = "chores_tab_all"
        const val KEY_MONTH_EVERYONE = "month_everyone"
        /** Before events and chores were split: both, for these members. */
        const val KEY_MONTH_MEMBERS = "month_members"
        const val KEY_MONTH_EVENT_MEMBERS = "month_event_members"
        const val KEY_MONTH_CHORE_MEMBERS = "month_chore_members"
        /** The versionCode whose release notes were last shown (Phase 8). */
        const val KEY_NOTES_SEEN = "notes_seen_version"
    }
}
