package app.everyday.android.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class EventDaysTest {
    private val saturday = 0b0100000
    private fun day(text: String) = LocalDate.parse(text)

    @Test
    fun `a one-day event is only on its own days, with its own times`() {
        val gym = Occurs(0b0000001, null, 600, 660)
        assertTrue(gym.isOn(day("2026-10-12")))
        assertFalse(gym.isOn(day("2026-10-13")))
        assertEquals(DayPart(600, 660), gym.partOn(day("2026-10-12")))
    }

    @Test
    fun `a one-off weekend away covers its days, from the start until the end`() {
        // Fri 23 Oct 18:00 to Sun 25 Oct 20:00.
        val away = Occurs(0, day("2026-10-23"), 18 * 60, 20 * 60, endDays = 2)
        assertFalse(away.isOn(day("2026-10-22")))
        assertEquals(DayPart(18 * 60, MINUTES_PER_DAY, fromBefore = false, goesOn = true), away.partOn(day("2026-10-23")))
        val middle = away.partOn(day("2026-10-24"))!!
        assertTrue(middle.allDay)
        assertTrue(middle.fromBefore && middle.goesOn)
        assertEquals(DayPart(0, 20 * 60, fromBefore = true, goesOn = false), away.partOn(day("2026-10-25")))
        assertFalse(away.isOn(day("2026-10-26")))
    }

    @Test
    fun `a weekly event wraps into the next week, and the end can be before the start time`() {
        // Every Saturday 22:00 to Monday 07:00.
        val e = Occurs(saturday, null, 22 * 60, 7 * 60, endDays = 2)
        val monday = day("2026-10-19")
        assertTrue(e.isOn(monday))
        assertEquals(listOf(day("2026-10-17")), e.startsCovering(monday))
        assertEquals(DayPart(0, 7 * 60, fromBefore = true, goesOn = false), e.partOn(monday))
        assertFalse(e.isOn(day("2026-10-20")))
    }

    @Test
    fun `an all-day multi-day event is all day on each of its days`() {
        val e = Occurs(0, day("2026-09-29"), null, null, endDays = 3)
        // Starts in the previous month, still showing in this one.
        val part = e.partOn(day("2026-10-02"))!!
        assertTrue(part.allDay)
        assertTrue(part.fromBefore)
        assertFalse(part.goesOn)
        assertNull(e.partOn(day("2026-10-03")))
    }
}
