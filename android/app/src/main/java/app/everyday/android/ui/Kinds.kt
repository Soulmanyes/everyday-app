package app.everyday.android.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector

/*
 * Chore reminders look like chores (the chore card colour and the Chores tab's house icon), so they
 * stand apart from normal reminders, which use the Reminders tab's icon and are the ones that can be shared.
 */

val ReminderIcon: ImageVector = Icons.Default.DateRange
val ChoreIcon: ImageVector = Icons.Default.Home

fun kindIcon(choreId: String?): ImageVector = if (choreId != null) ChoreIcon else ReminderIcon

/**
 * Background and content colours of a chore card, also used for chore reminders. A fixed green, not
 * a theme colour: the wallpaper-based dynamic theme makes every container a shade of one hue, which
 * left chores looking like reminders.
 */
fun ColorScheme.choreColors(): Pair<Color, Color> =
    if (background.luminance() < 0.5f) Color(0xFF2E4A33) to Color(0xFFCDEBD0) else Color(0xFFCDEBD0) to Color(0xFF1B4D24)

/** Events are the Reminders tab's own kind; chores and chore reminders use the house icon. */
val EventIcon: ImageVector = ReminderIcon
