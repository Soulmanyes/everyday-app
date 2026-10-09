package app.everyday.android.ui

import android.content.Context
import androidx.core.content.edit
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

    private companion object {
        const val KEY_FOLLOW = "follow_phone_theme"
    }
}
