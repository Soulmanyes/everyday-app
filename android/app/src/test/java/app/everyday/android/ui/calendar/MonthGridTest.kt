package app.everyday.android.ui.calendar

import app.everyday.android.data.BusyEventEntity
import app.everyday.android.data.ChoreAssignmentEntity
import app.everyday.android.data.ChoreCompletion
import app.everyday.android.data.ChoreEntity
import app.everyday.android.data.ChoreWithDetails
import app.everyday.android.data.EventEntity
import app.everyday.android.data.EventMemberEntity
import app.everyday.android.data.EventView
import app.everyday.android.data.MemberEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

class MonthGridTest {
    private val me = "sam"
    private val everyDay = 0b1111111

    private fun event(
        id: String,
        owner: String = me,
        weekdays: Int = everyDay,
        onDate: String? = null,
        start: Int? = null,
        sharedWith: List<String> = emptyList(),
        hidden: Boolean = false,
    ) = EventView(
        EventEntity(id, owner, id, weekdays = if (onDate != null) 0 else weekdays, onDate = onDate, startMinute = start, createdAt = 1, updatedAt = 1),
        sharedWith.map { EventMemberEntity(id, it, hidden = hidden) },
        me,
        emptyList(),
    )

    private fun chore(id: String, weekdays: Int = everyDay, vararg assignees: String) = ChoreWithDetails(
        ChoreEntity(id, id, null, weekdays, ChoreCompletion.ANY),
        assignees.mapIndexed { i, m -> ChoreAssignmentEntity(id, m, i) },
        emptyList(),
    )

    @Test
    fun `month grid is whole weeks from Monday`() {
        // October 2026 starts on a Thursday and ends on a Saturday.
        val days = monthDays(YearMonth.of(2026, 10))
        assertEquals(LocalDate.of(2026, 9, 28), days.first())
        assertEquals(LocalDate.of(2026, 11, 1), days.last())
        assertEquals(35, days.size)
        assertTrue(days.first().dayOfWeek == DayOfWeek.MONDAY)
        // February 2027 starts on a Monday and has exactly four weeks.
        assertEquals(28, monthDays(YearMonth.of(2027, 2)).size)
        // August 2026 needs six rows.
        assertEquals(42, monthDays(YearMonth.of(2026, 8)).size)
    }

    @Test
    fun `items on a day follow weekdays and dates, events before chores`() {
        val friday = LocalDate.of(2026, 10, 9)
        val events = listOf(
            event("late", start = 18 * 60),
            event("early", start = 8 * 60),
            event("allday"),
            event("once", onDate = "2026-10-09"),
            event("othermonth", onDate = "2026-11-09"),
            event("mondays", weekdays = 0b0000001),
        )
        val chores = listOf(chore("bins", 0b0010000, me), chore("weekend", 0b1100000, me))
        val keys = monthItemsOn(friday, events, chores, MonthFilter()).map { it.title }
        assertEquals(listOf("allday", "once", "early", "late", "bins"), keys)
    }

    @Test
    fun `filter matches owners, people shared with and assignees`() {
        val day = LocalDate.of(2026, 10, 9)
        val events = listOf(
            event("mine"),
            event("alexs", owner = "alex"),
            event("sharedWithAlex", sharedWith = listOf("alex")),
            event("alexLeft", sharedWith = listOf("alex"), hidden = true),
        )
        val chores = listOf(chore("dishes", everyDay, me), chore("bins", everyDay, me, "alex"))
        val alexOnly = MonthFilter(everyone = false, memberIds = setOf("alex"))
        assertEquals(listOf("alexs", "sharedWithAlex", "bins"), monthItemsOn(day, events, chores, alexOnly).map { it.title })
        assertEquals(6, monthItemsOn(day, events, chores, MonthFilter()).size)
    }

    @Test
    fun `removed members drop out of the filter, and nobody left means everyone`() {
        val members = listOf(MemberEntity("sam", "Sam", 1), MemberEntity("alex", "Alex", 1))
        assertEquals(
            MonthFilter(everyone = false, memberIds = setOf("alex")),
            effectiveFilter(MonthFilter(everyone = false, memberIds = setOf("alex", "gone")), members),
        )
        assertEquals(MonthFilter(), effectiveFilter(MonthFilter(everyone = false, memberIds = setOf("gone")), members))
    }

    @Test
    fun `Busy events sort with events by time and follow their owner in the filter`() {
        val day = LocalDate.of(2026, 10, 9)
        val busy = listOf(
            BusyEventEntity(me, "b1", "alex", weekdays = everyDay, startMinute = 9 * 60),
            BusyEventEntity(me, "b2", "jo", onDate = "2026-10-09"),
            BusyEventEntity(me, "b3", "alex", onDate = "2026-10-10"),
        )
        val events = listOf(event("early", start = 8 * 60), event("late", start = 18 * 60))
        val items = monthItemsOn(day, events, listOf(chore("bins", everyDay, me)), MonthFilter(), busy)
        assertEquals(listOf("busy:b2", "event:early", "busy:b1", "event:late", "chore:bins"), items.map { it.key })
        val alexOnly = MonthFilter(everyone = false, memberIds = setOf("alex"))
        assertEquals(listOf("busy:b1"), monthItemsOn(day, events, emptyList(), alexOnly, busy).map { it.key })
    }
}
