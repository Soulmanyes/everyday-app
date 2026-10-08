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
}
