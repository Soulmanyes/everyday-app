package app.everyday.android.ui.calendar

import app.everyday.android.data.BusyEventEntity
import app.everyday.android.data.ChoreWithDetails
import app.everyday.android.data.EventView
import app.everyday.android.data.occurs
import app.everyday.android.reminders.Occurs
import app.everyday.android.reminders.isOnWeekday
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/**
 * Whose chores and events the month view shows (Phase 7): everyone, or per member their events
 * ([eventMemberIds], Busy ones included) and their chores ([choreMemberIds]), each on or off.
 * Kept apart from the member list so members added later show while [everyone] is on.
 */
data class MonthFilter(
    val everyone: Boolean = true,
    val eventMemberIds: Set<String> = emptySet(),
    val choreMemberIds: Set<String> = emptySet(),
) {
    /** Members with their events or chores (or both) showing. */
    val memberIds get() = eventMemberIds + choreMemberIds

    companion object {
        /** Both the events and the chores of [ids]. */
        fun of(ids: Set<String>) = MonthFilter(everyone = false, eventMemberIds = ids, choreMemberIds = ids)
    }
}

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

    /** Another member's event shown as Busy (times only), or with its title when shared for scheduling. */
    data class Busy(val busy: BusyEventEntity) : MonthItem {
        override val key get() = "busy:${busy.id}"
        override val title get() = busy.title ?: "Busy"
    }
}

/** The days the grid shows for [month]: whole weeks, Monday first, so 4 to 6 rows. */
fun monthDays(month: YearMonth): List<LocalDate> {
    val first = month.atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val last = month.atEndOfMonth().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
    return generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.toList()
}

/** An event matches the filter if its owner or anyone it's shared with has their events showing. */
fun MonthFilter.matches(view: EventView): Boolean =
    everyone || view.event.ownerMemberId in eventMemberIds || view.takingPart.any { it.memberId in eventMemberIds }

/**
 * A Busy event matches the filter if its owner has their events showing. One shared with me for scheduling
 * (it has a title, Phase 9.8) always shows, like the events I take part in.
 */
fun MonthFilter.matches(busy: BusyEventEntity): Boolean = everyone || busy.title != null || busy.ownerMemberId in eventMemberIds

/** Any day a Busy event covers, a multi-day one included. */
fun BusyEventEntity.isOn(date: LocalDate) = occurs().isOn(date)

/** A chore matches the filter if any of its assignees has their chores showing. */
fun MonthFilter.matches(chore: ChoreWithDetails): Boolean = everyone || chore.assigneeIds.any { it in choreMemberIds }

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
    // A multi-day event sorts by its part on this day: all day in the middle, from midnight on its last day.
    val dated = events.filter { it.isOn(date) && filter.matches(it) }.map { Dated(MonthItem.Event(it), it.occurs.startOn(date)) } +
        busy.filter { it.isOn(date) && filter.matches(it) }.map { Dated(MonthItem.Busy(it), it.occurs().startOn(date)) }
    val dayChores = chores.filter { isOnWeekday(it.chore.weekdays, date) && filter.matches(it) }
    return dated.sortedWith(compareBy({ it.startMinute ?: -1 }, { it.item.title.lowercase() }, { it.item.key })).map { it.item } +
        dayChores.sortedBy { it.chore.title.lowercase() }.map { MonthItem.Chore(it) }
}

/** Where an occurrence starts on [date], null if it fills the whole day. */
private fun Occurs.startOn(date: LocalDate): Int? = partOn(date)?.takeUnless { it.allDay }?.fromMinute

/** An event or Busy event with its start (null = all day), for sorting. */
private data class Dated(val item: MonthItem, val startMinute: Int?)

/**
 * Whose events and Busy events on [items] are on a date rather than weekly: the month cell is outlined in
 * their colours. Each owner once, in the order first met.
 */
fun oneOffOwners(items: List<MonthItem>): Set<String> = items.mapNotNullTo(linkedSetOf()) {
    when (it) {
        is MonthItem.Event -> it.view.event.ownerMemberId.takeIf { _ -> it.view.event.onDate != null }
        is MonthItem.Busy -> it.busy.ownerMemberId.takeIf { _ -> it.busy.onDate != null }
        is MonthItem.Chore -> null
    }
}

/**
 * How many things each person has in a month cell (Phase 9.7): events and Busy events count for their
 * owner, chores for each assignee the filter shows. The cell shows one line per person.
 */
fun personCounts(items: List<MonthItem>, filter: MonthFilter): Map<String, Int> {
    val counts = linkedMapOf<String, Int>()
    fun add(id: String) { counts[id] = (counts[id] ?: 0) + 1 }
    for (item in items) {
        when (item) {
            is MonthItem.Event -> add(item.view.event.ownerMemberId)
            is MonthItem.Busy -> add(item.busy.ownerMemberId)
            is MonthItem.Chore -> item.details.assigneeIds.filter { filter.everyone || it in filter.choreMemberIds }.forEach(::add)
        }
    }
    return counts
}
