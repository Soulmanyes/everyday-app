package app.everyday.android.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector

/*
 * Chore reminders look like chores (the chore card colour and the Chores tab's house icon), so they
 * stand apart from events, which use the Events tab's icon.
 */

/** The Events tab's icon, also shown on events. */
val EventIcon: ImageVector = Icons.Default.DateRange
val ChoreIcon: ImageVector = Icons.Default.Home

/** The Reminders tab's icon, also the bell on events that remind the picked profile. */
val ReminderIcon: ImageVector = Icons.Default.Notifications

fun kindIcon(choreId: String?): ImageVector = if (choreId != null) ChoreIcon else EventIcon

/**
 * Background and content colours of a chore card, also used for chore reminders. A fixed green, not
 * a theme colour: the wallpaper-based dynamic theme makes every container a shade of one hue, which
 * left chores looking like reminders.
 */
fun ColorScheme.choreColors(): Pair<Color, Color> =
    if (background.luminance() < 0.5f) Color(0xFF2E4A33) to Color(0xFFCDEBD0) else Color(0xFFCDEBD0) to Color(0xFF1B4D24)
