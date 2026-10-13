package app.everyday.android.ui

import app.everyday.android.data.EventEntity
import app.everyday.android.data.occurs
import app.everyday.android.reminders.Occurs
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

/**
 * "30 min before", "1 h before", "1 h 30 min before", "2 days before", "1 day 3 h before" or "At start" for a
 * reminder relative to an event's start (up to 7 days, Phase 10).
 */
fun formatBefore(minutes: Int): String {
    if (minutes == 0) return "At start"
    val days = minutes / MINUTES_PER_DAY
    val hours = minutes % MINUTES_PER_DAY / 60
    val mins = minutes % 60
    val parts = listOfNotNull(
        days.takeIf { it > 0 }?.let { if (it == 1) "1 day" else "$it days" },
        hours.takeIf { it > 0 }?.let { "$it h" },
        mins.takeIf { it > 0 }?.let { "$it min" },
    )
    return parts.joinToString(" ") + " before"
}

/** When an event happens: "Tue, Thu · 17:00 – 18:00", "Mon 20 Oct · All day", "Every day · 08:00". */
fun formatEventWhen(event: EventEntity): String {
    if (event.endDays > 0) return formatSpan(event.occurs())
    val days = event.onDate?.let { LocalDate.parse(it).format(DateTimeFormatter.ofPattern("EEE d MMM yyyy")) } ?: formatWeekdays(event.weekdays)
    val time = when {
        event.startMinute == null -> "All day"
        event.endMinute == null -> formatMinute(event.startMinute)
        else -> "${formatMinute(event.startMinute)} – ${formatMinute(event.endMinute)}"
    }
    return "$days · $time"
}

/**
 * A multi-day event's span (Phase 9.6): "Fri 23 Oct 18:00 – Sun 25 Oct 20:00", "Every Fri 18:00 – Sun 20:00",
 * or "Fri 23 Oct – Sun 25 Oct · All day". Times show only when it has a start.
 */
fun formatSpan(o: Occurs): String {
    val dated = DateTimeFormatter.ofPattern("EEE d MMM")
    val (first, last) = o.onDate?.let { it.format(dated) to it.plusDays(o.endDays.toLong()).format(dated) } ?: run {
        val day = DayOfWeek.of(Integer.numberOfTrailingZeros(o.weekdays) % 7 + 1)
        "Every " + dayShort(day) to dayShort(day.plus(o.endDays.toLong()))
    }
    if (o.startMinute == null) return "$first – $last · All day"
    return "$first ${formatMinute(o.startMinute)} – $last ${o.endMinute?.let(::formatMinute).orEmpty()}".trimEnd()
}

/** A persistent event reminder's lead: "From 30 min before until it ends", or "until it starts" for an event with no end time. */
fun formatLead(minutes: Int, hasEnd: Boolean): String {
    val until = if (hasEnd) "until it ends" else "until it starts"
    return if (minutes == 0) "From the start $until" else "From ${formatBefore(minutes).removeSuffix(" before")} before $until"
}
