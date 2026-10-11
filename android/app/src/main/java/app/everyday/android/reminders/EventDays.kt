package app.everyday.android.reminders

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * When an event happens: weekly on [weekdays] or once on [onDate], from [startMinute] (null = all day),
 * ending [endDays] after the day it starts (Phase 9.6; 0 = the same day) at [endMinute]. A multi-day
 * event's [endMinute] is on its last day, so it can be at or before [startMinute].
 *
 * Everything that places events on days (alarms, week and month views, Busy) asks this, so they agree.
 */
data class Occurs(
    val weekdays: Int,
    val onDate: LocalDate?,
    val startMinute: Int?,
    val endMinute: Int?,
    val endDays: Int = 0,
) {
    val multiDay get() = endDays > 0

    /** Whether an occurrence starts on [date]. */
    fun startsOn(date: LocalDate) = onDate?.let { it == date } ?: isOnWeekday(weekdays, date)

    /** The start days of the occurrences that cover [date], latest first. */
    fun startsCovering(date: LocalDate): List<LocalDate> = (0..endDays).map { date.minusDays(it.toLong()) }.filter(::startsOn)

    /** Whether an occurrence covers [date]. */
    fun isOn(date: LocalDate) = startsCovering(date).isNotEmpty()

    /** The part on [date] of the occurrence that starts on [start] (which must cover [date]). */
    fun partOn(start: LocalDate, date: LocalDate): DayPart {
        if (!multiDay) return DayPart(startMinute, endMinute)
        val offset = ChronoUnit.DAYS.between(start, date).toInt()
        val first = offset == 0
        val last = offset == endDays
        if (startMinute == null) return DayPart(null, null, fromBefore = !first, goesOn = !last)
        return DayPart(
            fromMinute = if (first) startMinute else 0,
            toMinute = if (last) endMinute else MINUTES_PER_DAY,
            fromBefore = !first,
            goesOn = !last,
        )
    }

    /** The part on [date] of the latest occurrence covering it, or null if none does. */
    fun partOn(date: LocalDate): DayPart? = startsCovering(date).firstOrNull()?.let { partOn(it, date) }
}

/**
 * What an event fills of one day: from [fromMinute] (null = all day) until [toMinute] (null = no end time).
 * A multi-day event [fromBefore] began on an earlier day, and [goesOn] to a later one.
 */
data class DayPart(val fromMinute: Int?, val toMinute: Int?, val fromBefore: Boolean = false, val goesOn: Boolean = false) {
    val allDay get() = fromMinute == null || (fromMinute == 0 && toMinute == MINUTES_PER_DAY)
}
