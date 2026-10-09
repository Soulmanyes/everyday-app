package app.everyday.android.ui.calendar

import app.everyday.android.data.BusyEventEntity
import app.everyday.android.data.ChoreWithDetails
import app.everyday.android.data.EventView
import app.everyday.android.reminders.isOnWeekday
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/**
 * Whose chores and events the month view shows (Phase 7): everyone, or the members in [memberIds].
 * Kept apart from the member list so members added later show while [everyone] is on.
 */
data class MonthFilter(val everyone: Boolean = true, val memberIds: Set<String> = emptySet())

/** Something in a month cell: an event the picked profile sees, or a household chore. */
sealed interface MonthItem {
    /** Selection key, unique across both kinds. */
    val key: String
    val title: String

    data class Event(val view: EventView) : MonthItem {
        override val key get() = "event:${view.event.id}"
        override val title get() = view.event.title
    }

    data class Chore(val details: ChoreWithDetails) : MonthItem {
        override val key get() = "chore:${details.chore.id}"
        override val title get() = details.chore.title
    }

    /** Another member's personal event shown as Busy: times only. */
    data class Busy(val busy: BusyEventEntity) : MonthItem {
        override val key get() = "busy:${busy.id}"
        override val title get() = "Busy"
    }
}

/** The days the grid shows for [month]: whole weeks, Monday first, so 4 to 6 rows. */
fun monthDays(month: YearMonth): List<LocalDate> {
    val first = month.atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val last = month.atEndOfMonth().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
    return generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.toList()
}

/** An event matches the filter if its owner or anyone it's shared with is picked. */
fun MonthFilter.matches(view: EventView): Boolean =
    everyone || view.event.ownerMemberId in memberIds || view.members.any { !it.hidden && it.memberId in memberIds }

/** A Busy event matches the filter if its owner is picked. */
fun MonthFilter.matches(busy: BusyEventEntity): Boolean = everyone || busy.ownerMemberId in memberIds

fun BusyEventEntity.isOn(date: LocalDate) = onDate?.let { it == date.toString() } ?: isOnWeekday(weekdays, date)

/** A chore matches the filter if any of its assignees is picked. */
fun MonthFilter.matches(chore: ChoreWithDetails): Boolean = everyone || chore.assigneeIds.any { it in memberIds }

/**
 * What a month cell shows on [date]: events and Busy events first (all day, then by start time), then
 * chores, which repeat every week and would otherwise push a date's events out of the cell; ties by title.
 */
fun monthItemsOn(
    date: LocalDate,
    events: List<EventView>,
    chores: List<ChoreWithDetails>,
    filter: MonthFilter,
    busy: List<BusyEventEntity> = emptyList(),
): List<MonthItem> {
    val dated = events.filter { it.isOn(date) && filter.matches(it) }.map { Dated(MonthItem.Event(it), it.event.startMinute) } +
        busy.filter { it.isOn(date) && filter.matches(it) }.map { Dated(MonthItem.Busy(it), it.startMinute) }
    val dayChores = chores.filter { isOnWeekday(it.chore.weekdays, date) && filter.matches(it) }
    return dated.sortedWith(compareBy({ it.startMinute ?: -1 }, { it.item.title.lowercase() }, { it.item.key })).map { it.item } +
        dayChores.sortedBy { it.chore.title.lowercase() }.map { MonthItem.Chore(it) }
}

/** An event or Busy event with its start (null = all day), for sorting. */
private data class Dated(val item: MonthItem, val startMinute: Int?)
