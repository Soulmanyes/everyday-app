package app.everyday.android.reminders

import app.everyday.android.data.ReminderMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** A time window in minutes since midnight: start in 0..1439, end in 1..1440, end > start. */
data class Window(val startMinute: Int, val endMinute: Int) {
    val isAllDay get() = startMinute == 0 && endMinute == MINUTES_PER_DAY
}

const val MINUTES_PER_DAY = 1440

/**
 * Everything the scheduler needs to know about one reminder, free of Android and Room types. An event
 * reminder carries its event's days and times: [minutesBefore] and [leadMinutes] count back from
 * [eventStart] on the event's date, so they can fall on the day before.
 *
 * A multi-day event (Phase 9.6) covers [eventEndDays] more days: fixed times and [windows] happen on each
 * of them, while [minutesBefore] and the lead count back from the first day's start. With [wholeEvent], a
 * persistent reminder shows from the lead (or the first day's midnight) until the last day's end.
 */
data class ReminderSpec(
    val id: String,
    val mode: ReminderMode,
    /** Bit 0 = Monday ... bit 6 = Sunday. Ignored when [onDate] is set. */
    val weekdays: Int,
    val active: Boolean,
    /** Fixed times of day (timed). */
    val times: List<Int> = emptyList(),
    /** Fixed windows (persistent): a chore reminder's windows, or all day. */
    val windows: List<Window> = emptyList(),
    /** A one-off event's date: the reminder happens only then. */
    val onDate: LocalDate? = null,
    /** Timed: minutes before [eventStart]. */
    val minutesBefore: List<Int> = emptyList(),
    val eventStart: Int? = null,
    val eventEnd: Int? = null,
    /** Persistent: shows from this many minutes before [eventStart] until [eventEnd] (or the start). */
    val leadMinutes: Int? = null,
    val doneOn: LocalDate? = null,
    val snoozeUntil: Instant? = null,
    /** Days its chore is already done for the owner (chore reminders only); it stays silent on those days. */
    val choreDoneOn: Set<LocalDate> = emptySet(),
    val eventEndDays: Int = 0,
    /** Persistent on a multi-day event: one span over the whole event (Done hides it for a day at a time). */
    val wholeEvent: Boolean = false,
)

data class ScheduleResult(
    /** Timed reminders (including expired snoozes) whose notification should be posted now. */
    val dueTimed: Set<String>,
    /** Reminders whose snooze has expired and should be cleared, whether or not they were posted. */
    val expiredSnoozes: Set<String>,
    /** Persistent reminders that should be showing right now. */
    val visiblePersistent: Set<String>,
    /** When to run again, or null if nothing is scheduled. */
    val nextWakeAt: Instant?,
)

/**
 * Pure scheduling logic. The app keeps one alarm for [ScheduleResult.nextWakeAt] and calls [compute]
 * again whenever it fires or anything changes, so this function must be idempotent: calling it twice
 * with the same inputs gives the same answer.
 */
object ReminderScheduler {
    /** Missed timed events older than this (phone off, alarm delayed) are skipped, not posted late. */
    val GRACE: Duration = Duration.ofMinutes(30)

    private const val LOOKAHEAD_DAYS = 8L

    fun compute(reminders: List<ReminderSpec>, now: Instant, zone: ZoneId, lastCheck: Instant?): ScheduleResult {
        val today = now.atZone(zone).toLocalDate()
        // Timed events in (from, now] are due. On first run there is no history, so nothing is due.
        val from = maxOf(lastCheck ?: now, now.minus(GRACE))

        val due = mutableSetOf<String>()
        val expired = mutableSetOf<String>()
        val visible = mutableSetOf<String>()
        var next: Instant? = null
        fun candidate(t: Instant) {
            if (t.isAfter(now) && (next == null || t.isBefore(next))) next = t
        }

        for (r in reminders) {
            r.snoozeUntil?.let { until ->
                val choreDone = until.atZone(zone).toLocalDate() in r.choreDoneOn
                if (!until.isAfter(now)) {
                    expired += r.id
                    if (r.active && !choreDone && until.isAfter(from)) due += r.id
                } else if (r.active && !choreDone) {
                    candidate(until)
                }
            }
            if (!r.active || (r.weekdays == 0 && r.onDate == null)) continue

            when (r.mode) {
                ReminderMode.TIMED -> {
                    // "Before" times can be up to 7 days ahead of an occurrence, so look that much further for those.
                    val extra = if (r.minutesBefore.isEmpty()) 0L else (r.minutesBefore.max() + MINUTES_PER_DAY - 1L) / MINUTES_PER_DAY
                    for (date in dates(today.minusDays(1), today.plusDays(LOOKAHEAD_DAYS + extra))) {
                        if (date in r.choreDoneOn) continue
                        // Fixed times on every day the event covers; "before" times only before an occurrence starts.
                        val fixed = if (r.isOn(date)) r.times.map { instantAt(date, it, zone) } else emptyList()
                        val relative = r.eventStart?.takeIf { r.startsOn(date) }?.let { start ->
                            val at = instantAt(date, start, zone)
                            r.minutesBefore.map { at.minus(Duration.ofMinutes(it.toLong())) }
                        }.orEmpty()
                        for (t in fixed + relative) {
                            if (t.isAfter(from) && !t.isAfter(now)) due += r.id
                            candidate(t)
                        }
                    }
                }
                ReminderMode.PERSISTENT -> {
                    // A whole-event span that is still going can have started up to its length ago.
                    for (date in dates(today.minusDays(1L + r.eventEndDays), today.plusDays(LOOKAHEAD_DAYS))) {
                        if (date in r.choreDoneOn) continue
                        val windows = if (r.isOn(date)) {
                            r.windows.map { instantAt(date, it.startMinute, zone) to instantAt(date, it.endMinute, zone) }
                        } else {
                            emptyList()
                        }
                        for ((start, end) in windows) {
                            // Done hides it for that day, or for the day the window started on.
                            if (r.doneOn == date || r.doneOn == start.atZone(zone).toLocalDate()) continue
                            if (!start.isAfter(now) && end.isAfter(now)) visible += r.id
                            candidate(start)
                            candidate(end)
                        }
                        val (start, end) = (if (r.startsOn(date)) r.leadSpan(date, zone) else null) ?: continue
                        if (!end.isAfter(now)) continue
                        candidate(start)
                        candidate(end)
                        if (start.isAfter(now)) continue
                        // Done hides it for the rest of the day it was pressed on; a lead that began the evening
                        // before keeps it hidden through the event's first day, as for a one-day event.
                        val nowDate = now.atZone(zone).toLocalDate()
                        val hidden = r.doneOn == nowDate || (r.doneOn == start.atZone(zone).toLocalDate() && nowDate == date)
                        if (!hidden) visible += r.id
                        // It may show again (or Done may wear off) at the next midnight.
                        candidate(instantAt(nowDate, MINUTES_PER_DAY, zone))
                    }
                }
            }
        }
        return ScheduleResult(due, expired, visible, next)
    }

    /** The instant a local wall-clock minute happens. Gaps (DST forward) shift later; overlaps use the first. */
    fun instantAt(date: LocalDate, minute: Int, zone: ZoneId): Instant {
        val dateTime = if (minute >= MINUTES_PER_DAY) {
            LocalDateTime.of(date.plusDays(1), LocalTime.MIDNIGHT)
        } else {
            LocalDateTime.of(date, LocalTime.of(minute / 60, minute % 60))
        }
        return ZonedDateTime.of(dateTime, zone).toInstant()
    }

    private fun ReminderSpec.occurs() = Occurs(weekdays, onDate, eventStart, eventEnd, eventEndDays)

    /** Whether it happens on [date]: every day a multi-day event covers. */
    private fun ReminderSpec.isOn(date: LocalDate) = occurs().isOn(date)

    /** Whether an occurrence starts on [date]. */
    private fun ReminderSpec.startsOn(date: LocalDate) = occurs().startsOn(date)

    /**
     * From [ReminderSpec.leadMinutes] before the start of the occurrence on [date] until its end (or its start).
     * A whole-event reminder without a lead starts at that day's midnight; a multi-day one ends on the last day.
     */
    private fun ReminderSpec.leadSpan(date: LocalDate, zone: ZoneId): Pair<Instant, Instant>? {
        val lead = leadMinutes
        val begin = when {
            lead != null -> instantAt(date, eventStart ?: return null, zone).minus(Duration.ofMinutes(lead.toLong()))
            wholeEvent -> instantAt(date, 0, zone)
            else -> return null
        }
        val lastDay = date.plusDays(eventEndDays.toLong())
        val endMinute = eventEnd ?: if (eventEndDays > 0 || eventStart == null) MINUTES_PER_DAY else eventStart
        val end = instantAt(lastDay, endMinute, zone)
        return if (end.isAfter(begin)) begin to end else null
    }

    private fun dates(first: LocalDate, last: LocalDate) =
        generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }
}

fun isOnWeekday(mask: Int, date: LocalDate): Boolean = mask and (1 shl (date.dayOfWeek.value - 1)) != 0
