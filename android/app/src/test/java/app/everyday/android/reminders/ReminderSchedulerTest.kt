package app.everyday.android.reminders

import app.everyday.android.data.ReminderMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderSchedulerTest {
    private val london = ZoneId.of("Europe/London")
    private val everyDay = 0b1111111
    private val mondayOnly = 0b0000001

    private fun at(text: String): Instant = ZonedDateTime.of(LocalDateTime.parse(text), london).toInstant()
    private fun hm(h: Int, m: Int = 0) = h * 60 + m

    private fun timed(id: String = "t", weekdays: Int = everyDay, vararg times: Int) =
        ReminderSpec(id, ReminderMode.TIMED, weekdays, active = true, times = times.toList())

    private fun persistent(id: String = "p", weekdays: Int = everyDay, vararg windows: Window) =
        ReminderSpec(id, ReminderMode.PERSISTENT, weekdays, active = true, windows = windows.toList())

    private fun compute(rs: List<ReminderSpec>, now: String, lastCheck: String?) =
        ReminderScheduler.compute(rs, at(now), london, lastCheck?.let { at(it) })

    @Test
    fun `timed reminder is due once its time passes`() {
        val r = timed(times = intArrayOf(hm(8)))
        assertTrue(compute(listOf(r), "2026-10-07T07:59", "2026-10-07T07:50").dueTimed.isEmpty())
        assertEquals(setOf("t"), compute(listOf(r), "2026-10-07T08:00", "2026-10-07T07:59").dueTimed)
        // Already handled at the previous check: not due again.
        assertTrue(compute(listOf(r), "2026-10-07T08:05", "2026-10-07T08:00").dueTimed.isEmpty())
    }

    @Test
    fun `next wake is the earliest upcoming time`() {
        val r = timed(times = intArrayOf(hm(8), hm(20)))
        assertEquals(at("2026-10-07T20:00"), compute(listOf(r), "2026-10-07T09:00", null).nextWakeAt)
        assertEquals(at("2026-10-08T08:00"), compute(listOf(r), "2026-10-07T21:00", null).nextWakeAt)
    }

    @Test
    fun `weekday mask skips to the next matching day across the week boundary`() {
        // 2026-10-11 is a Sunday; the next Monday is 2026-10-12.
        val r = timed(weekdays = mondayOnly, times = intArrayOf(hm(9)))
        assertEquals(at("2026-10-12T09:00"), compute(listOf(r), "2026-10-11T23:00", null).nextWakeAt)
    }

    @Test
    fun `missed events older than the grace period are skipped`() {
        val r = timed(times = intArrayOf(hm(8)))
        // Phone was off since yesterday; 08:00 was 2 hours ago.
        assertTrue(compute(listOf(r), "2026-10-07T10:00", "2026-10-06T12:00").dueTimed.isEmpty())
        // 08:00 was 10 minutes ago: still posted.
        assertEquals(setOf("t"), compute(listOf(r), "2026-10-07T08:10", "2026-10-06T12:00").dueTimed)
    }

    @Test
    fun `first run posts nothing`() {
        val r = timed(times = intArrayOf(hm(8)))
        assertTrue(compute(listOf(r), "2026-10-07T08:00", null).dueTimed.isEmpty())
    }

    @Test
    fun `dst spring forward shifts a time in the gap later`() {
        // 2026-03-29: London clocks go from 01:00 GMT to 02:00 BST. 01:30 does not exist.
        val r = timed(times = intArrayOf(hm(1, 30)))
        val next = compute(listOf(r), "2026-03-29T00:30", null).nextWakeAt
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), next) // 02:30 BST
    }

    @Test
    fun `dst fall back fires a repeated time only once`() {
        // 2026-10-25: 01:00-02:00 happens twice. 01:30 should fire at the first one only.
        val r = timed(times = intArrayOf(hm(1, 30)))
        val first = Instant.parse("2026-10-25T00:30:00Z") // 01:30 BST
        val second = Instant.parse("2026-10-25T01:30:00Z") // 01:30 GMT
        val now = Instant.parse("2026-10-25T00:00:00Z")
        assertEquals(first, ReminderScheduler.compute(listOf(r), now, london, now).nextWakeAt)
        val afterSecond = ReminderScheduler.compute(listOf(r), second, london, first)
        assertTrue(afterSecond.dueTimed.isEmpty())
    }

    @Test
    fun `persistent reminder is visible when now is inside a window`() {
        val r = persistent(windows = arrayOf(Window(hm(16), hm(17)), Window(hm(18), hm(19))))
        val inside = compute(listOf(r), "2026-10-07T16:30", null)
        assertEquals(setOf("p"), inside.visiblePersistent)
        assertEquals(at("2026-10-07T17:00"), inside.nextWakeAt)
        val between = compute(listOf(r), "2026-10-07T17:30", null)
        assertTrue(between.visiblePersistent.isEmpty())
        assertEquals(at("2026-10-07T18:00"), between.nextWakeAt)
    }

    @Test
    fun `window end is exclusive and start is inclusive`() {
        val r = persistent(windows = arrayOf(Window(hm(16), hm(17))))
        assertEquals(setOf("p"), compute(listOf(r), "2026-10-07T16:00", null).visiblePersistent)
        assertTrue(compute(listOf(r), "2026-10-07T17:00", null).visiblePersistent.isEmpty())
    }

    @Test
    fun `done today hides the reminder until the next day`() {
        val r = persistent(windows = arrayOf(Window(hm(16), hm(17))))
            .copy(doneOn = LocalDate.parse("2026-10-07"))
        val result = compute(listOf(r), "2026-10-07T16:30", null)
        assertTrue(result.visiblePersistent.isEmpty())
        assertEquals(at("2026-10-08T16:00"), result.nextWakeAt)
    }

    @Test
    fun `all day window is visible until midnight`() {
        val r = persistent(windows = arrayOf(Window(0, MINUTES_PER_DAY)))
        val late = compute(listOf(r), "2026-10-07T23:59", null)
        assertEquals(setOf("p"), late.visiblePersistent)
        assertEquals(at("2026-10-08T00:00"), late.nextWakeAt)
        assertEquals(setOf("p"), compute(listOf(r), "2026-10-08T00:00", null).visiblePersistent)
    }

    @Test
    fun `all day on a single weekday is hidden the next day`() {
        // 2026-10-12 is a Monday.
        val r = persistent(weekdays = mondayOnly, windows = arrayOf(Window(0, MINUTES_PER_DAY)))
        assertEquals(setOf("p"), compute(listOf(r), "2026-10-12T12:00", null).visiblePersistent)
        assertTrue(compute(listOf(r), "2026-10-13T00:00", null).visiblePersistent.isEmpty())
    }

    @Test
    fun `expired snooze is due and cleared, pending snooze is the next wake`() {
        val snoozed = timed(times = intArrayOf(hm(8))).copy(snoozeUntil = at("2026-10-07T08:10"))
        val pending = compute(listOf(snoozed), "2026-10-07T08:05", "2026-10-07T08:00")
        assertEquals(at("2026-10-07T08:10"), pending.nextWakeAt)
        assertTrue(pending.dueTimed.isEmpty())
        val fired = compute(listOf(snoozed), "2026-10-07T08:10", "2026-10-07T08:05")
        assertEquals(setOf("t"), fired.dueTimed)
        assertEquals(setOf("t"), fired.expiredSnoozes)
    }

    @Test
    fun `paused reminders do nothing and drop their snooze`() {
        val r = timed(times = intArrayOf(hm(8))).copy(active = false, snoozeUntil = at("2026-10-07T08:10"))
        val result = compute(listOf(r), "2026-10-07T08:15", "2026-10-07T07:00")
        assertTrue(result.dueTimed.isEmpty())
        assertEquals(setOf("t"), result.expiredSnoozes)
        assertNull(result.nextWakeAt)
    }

    @Test
    fun `no weekdays means never`() {
        val r = timed(weekdays = 0, times = intArrayOf(hm(8)))
        assertNull(compute(listOf(r), "2026-10-07T07:00", null).nextWakeAt)
    }

    @Test
    fun `chore done today silences a timed chore reminder until tomorrow`() {
        val today = LocalDate.parse("2026-10-07")
        val r = timed(times = intArrayOf(hm(8), hm(20))).copy(choreDoneOn = setOf(today))
        assertTrue(compute(listOf(r), "2026-10-07T20:00", "2026-10-07T19:59").dueTimed.isEmpty())
        // Tomorrow's 08:00 is the next event; today's 20:00 is skipped.
        assertEquals(at("2026-10-08T08:00"), compute(listOf(r), "2026-10-07T09:00", null).nextWakeAt)
        assertEquals(setOf("t"), compute(listOf(r), "2026-10-08T08:00", "2026-10-08T07:59").dueTimed)
    }

    @Test
    fun `chore done while a persistent chore reminder is showing hides it`() {
        val r = persistent(windows = arrayOf(Window(hm(16), hm(18))))
        assertEquals(setOf("p"), compute(listOf(r), "2026-10-07T17:00", null).visiblePersistent)
        val done = r.copy(choreDoneOn = setOf(LocalDate.parse("2026-10-07")))
        val result = compute(listOf(done), "2026-10-07T17:00", null)
        assertTrue(result.visiblePersistent.isEmpty())
        assertEquals(at("2026-10-08T16:00"), result.nextWakeAt)
    }

    @Test
    fun `chore done today drops a snooze that would expire today`() {
        val r = timed(times = intArrayOf(hm(8))).copy(
            snoozeUntil = at("2026-10-07T08:10"),
            choreDoneOn = setOf(LocalDate.parse("2026-10-07")),
        )
        val result = compute(listOf(r), "2026-10-07T08:10", "2026-10-07T08:05")
        assertTrue(result.dueTimed.isEmpty())
        assertEquals(setOf("t"), result.expiredSnoozes)
    }

    private fun eventTimed(start: Int?, vararg before: Int, onDate: LocalDate? = null, weekdays: Int = if (onDate == null) everyDay else 0) =
        ReminderSpec("e", ReminderMode.TIMED, weekdays, active = true, onDate = onDate, eventStart = start, minutesBefore = before.toList())

    @Test
    fun `minutes before the start fire on the day before when the event is just after midnight`() {
        // Thursday 00:15, 30 minutes before = Wednesday 23:45.
        val r = eventTimed(hm(0, 15), 30, weekdays = 0b0001000)
        assertEquals(at("2026-10-07T23:45"), compute(listOf(r), "2026-10-07T20:00", null).nextWakeAt)
        assertEquals(setOf("e"), compute(listOf(r), "2026-10-07T23:45", "2026-10-07T23:40").dueTimed)
    }

    @Test
    fun `a one-off event reminds only on its date`() {
        val r = eventTimed(hm(10), 60, onDate = LocalDate.parse("2026-10-09"))
        assertEquals(at("2026-10-09T09:00"), compute(listOf(r), "2026-10-07T12:00", null).nextWakeAt)
        assertNull(compute(listOf(r), "2026-10-09T09:30", null).nextWakeAt)
        // Eight days ahead is still inside the lookahead.
        val far = eventTimed(hm(10), 0, onDate = LocalDate.parse("2026-10-15"))
        assertEquals(at("2026-10-15T10:00"), compute(listOf(far), "2026-10-07T12:00", null).nextWakeAt)
    }

    @Test
    fun `minutes before an all-day event are ignored`() {
        assertNull(compute(listOf(eventTimed(null, 30)), "2026-10-07T12:00", null).nextWakeAt)
    }

    @Test
    fun `relative times count real minutes across a clock change`() {
        // 2026-03-29 01:00 GMT -> 02:00 BST. An event at 02:30 local is 01:30 GMT... which doesn't exist; it shifts to 02:30 BST
        // (01:30 UTC). 60 minutes before is 00:30 UTC = 00:30 GMT.
        val r = eventTimed(hm(2, 30), 60, onDate = LocalDate.parse("2026-03-29"))
        assertEquals(Instant.parse("2026-03-29T00:30:00Z"), compute(listOf(r), "2026-03-28T22:00", null).nextWakeAt)
    }

    @Test
    fun `a lead window shows from before the start until the end, or until the start`() {
        val r = ReminderSpec("p", ReminderMode.PERSISTENT, everyDay, active = true, eventStart = hm(17), eventEnd = hm(18), leadMinutes = 30)
        assertTrue(compute(listOf(r), "2026-10-07T16:20", null).visiblePersistent.isEmpty())
        assertEquals(setOf("p"), compute(listOf(r), "2026-10-07T16:30", null).visiblePersistent)
        assertEquals(setOf("p"), compute(listOf(r), "2026-10-07T17:59", null).visiblePersistent)
        assertEquals(at("2026-10-07T18:00"), compute(listOf(r), "2026-10-07T17:59", null).nextWakeAt)
        val noEnd = r.copy(eventEnd = null)
        assertEquals(at("2026-10-07T17:00"), compute(listOf(noEnd), "2026-10-07T16:40", null).nextWakeAt)
        assertTrue(compute(listOf(noEnd), "2026-10-07T17:00", null).visiblePersistent.isEmpty())
        // Done on the event's day hides it.
        assertTrue(compute(listOf(r.copy(doneOn = LocalDate.parse("2026-10-07"))), "2026-10-07T17:00", null).visiblePersistent.isEmpty())
    }

    @Test
    fun `done before midnight hides a lead window that started the day before`() {
        // Event Thursday 00:15-01:00 with a 30 minute lead: starts Wednesday 23:45. Done pressed Wednesday.
        val r = ReminderSpec(
            "p", ReminderMode.PERSISTENT, 0b0001000, active = true, eventStart = hm(0, 15), eventEnd = hm(1), leadMinutes = 30,
            doneOn = LocalDate.parse("2026-10-07"),
        )
        assertTrue(compute(listOf(r), "2026-10-08T00:30", null).visiblePersistent.isEmpty())
        assertEquals(setOf("p"), compute(listOf(r.copy(doneOn = null)), "2026-10-08T00:30", null).visiblePersistent)
    }

    // Phase 9.6: multi-day events. A weekend away, Fri 23 Oct 18:00 to Sun 25 Oct 20:00 (the clocks go
    // back in London early on Sunday 25 October).
    private val away = ReminderSpec(
        "away", ReminderMode.PERSISTENT, 0, active = true,
        onDate = LocalDate.parse("2026-10-23"), eventStart = hm(18), eventEnd = hm(20), eventEndDays = 2,
    )

    @Test
    fun `fixed times fire on every day of a multi-day event, before times only before it starts`() {
        val r = away.copy(mode = ReminderMode.TIMED, times = listOf(hm(8)), minutesBefore = listOf(60))
        assertEquals(setOf("away"), compute(listOf(r), "2026-10-23T17:00", "2026-10-23T16:59").dueTimed)
        // Every day it covers, the first one included (as the owner chose: 08:00 on Fri, Sat and Sun).
        assertEquals(setOf("away"), compute(listOf(r), "2026-10-23T08:00", "2026-10-23T07:59").dueTimed)
        assertTrue(compute(listOf(r), "2026-10-22T08:00", "2026-10-22T07:59").dueTimed.isEmpty())
        assertEquals(setOf("away"), compute(listOf(r), "2026-10-24T08:00", "2026-10-24T07:59").dueTimed)
        assertEquals(setOf("away"), compute(listOf(r), "2026-10-25T08:00", "2026-10-25T07:59").dueTimed)
        assertTrue(compute(listOf(r), "2026-10-26T08:00", "2026-10-26T07:59").dueTimed.isEmpty())
    }

    @Test
    fun `a whole-event persistent reminder shows from the lead until the last day's end`() {
        val r = away.copy(leadMinutes = 30, wholeEvent = true)
        assertTrue(compute(listOf(r), "2026-10-23T17:29", null).visiblePersistent.isEmpty())
        assertEquals(setOf("away"), compute(listOf(r), "2026-10-23T17:30", null).visiblePersistent)
        assertEquals(setOf("away"), compute(listOf(r), "2026-10-24T12:00", null).visiblePersistent)
        assertEquals(setOf("away"), compute(listOf(r), "2026-10-25T19:59", null).visiblePersistent)
        assertTrue(compute(listOf(r), "2026-10-25T20:00", null).visiblePersistent.isEmpty())
        // Without a lead it starts at the first day's midnight.
        val allDay = away.copy(wholeEvent = true)
        assertEquals(setOf("away"), compute(listOf(allDay), "2026-10-23T00:00", null).visiblePersistent)
    }

    @Test
    fun `Done hides a whole-event reminder until the next day, which is when it wakes up again`() {
        val r = away.copy(leadMinutes = 30, wholeEvent = true, doneOn = LocalDate.parse("2026-10-24"))
        val saturday = compute(listOf(r), "2026-10-24T12:00", null)
        assertTrue(saturday.visiblePersistent.isEmpty())
        assertEquals(at("2026-10-25T00:00"), saturday.nextWakeAt)
        assertEquals(setOf("away"), compute(listOf(r), "2026-10-25T00:00", null).visiblePersistent)
    }

    @Test
    fun `an each-day persistent reminder shows all day on every day of the event`() {
        val r = away.copy(windows = listOf(Window(0, MINUTES_PER_DAY)))
        assertTrue(compute(listOf(r), "2026-10-22T12:00", null).visiblePersistent.isEmpty())
        for (date in listOf("2026-10-23", "2026-10-24", "2026-10-25")) {
            assertEquals(date, setOf("away"), compute(listOf(r), "${date}T09:00", null).visiblePersistent)
        }
        assertTrue(compute(listOf(r), "2026-10-26T09:00", null).visiblePersistent.isEmpty())
        // Done hides only that day.
        val done = r.copy(doneOn = LocalDate.parse("2026-10-24"))
        assertTrue(compute(listOf(done), "2026-10-24T09:00", null).visiblePersistent.isEmpty())
        assertEquals(setOf("away"), compute(listOf(done), "2026-10-25T09:00", null).visiblePersistent)
    }

    @Test
    fun `a long event that started weeks ago still shows`() {
        val r = away.copy(onDate = LocalDate.parse("2026-10-01"), eventEndDays = 31, wholeEvent = true)
        assertEquals(setOf("away"), compute(listOf(r), "2026-10-30T12:00", null).visiblePersistent)
        assertTrue(compute(listOf(r), "2026-11-01T20:00", null).visiblePersistent.isEmpty())
    }
}
