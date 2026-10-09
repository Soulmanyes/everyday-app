package app.everyday.android.ui

import app.everyday.android.data.EventEntity
import app.everyday.android.reminders.MINUTES_PER_DAY
import app.everyday.android.reminders.Window
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

private val timeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

fun formatMinute(minute: Int): String =
    if (minute >= MINUTES_PER_DAY) "24:00" else LocalTime.of(minute / 60, minute % 60).format(timeFormatter)

fun formatWindow(window: Window): String =
    if (window.isAllDay) "All day" else "${formatMinute(window.startMinute)} – ${formatMinute(window.endMinute)}"

fun dayShort(day: DayOfWeek): String = day.getDisplayName(TextStyle.SHORT, Locale.getDefault())

fun dayLetter(day: DayOfWeek): String = day.getDisplayName(TextStyle.NARROW, Locale.getDefault())

/** "Mon, Wed, Fri", "Every day", "Weekdays" or "Weekends" for a weekday mask (bit 0 = Monday). */
fun formatWeekdays(mask: Int): String = when (mask) {
    0b1111111 -> "Every day"
    0b0011111 -> "Weekdays"
    0b1100000 -> "Weekends"
    0 -> "No days"
    else -> DayOfWeek.entries.filter { mask and (1 shl (it.value - 1)) != 0 }.joinToString(", ") { dayShort(it) }
}

/** "30 min before", "1 h before", "1 h 30 min before" or "At start" for a reminder relative to an event's start. */
fun formatBefore(minutes: Int): String = when {
    minutes == 0 -> "At start"
    minutes % 60 == 0 -> "${minutes / 60} h before"
    minutes > 60 -> "${minutes / 60} h ${minutes % 60} min before"
    else -> "$minutes min before"
}

/** When an event happens: "Tue, Thu · 17:00 – 18:00", "Mon 20 Oct · All day", "Every day · 08:00". */
fun formatEventWhen(event: EventEntity): String {
    val days = event.onDate?.let { LocalDate.parse(it).format(DateTimeFormatter.ofPattern("EEE d MMM yyyy")) } ?: formatWeekdays(event.weekdays)
    val time = when {
        event.startMinute == null -> "All day"
        event.endMinute == null -> formatMinute(event.startMinute)
        else -> "${formatMinute(event.startMinute)} – ${formatMinute(event.endMinute)}"
    }
    return "$days · $time"
}
