package app.everyday.android.data

import app.everyday.android.reminders.MINUTES_PER_DAY
import app.everyday.android.reminders.Window
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What an event reminder hands the scheduler, for one-day and multi-day events (Phase 9.6). */
class ToSpecTest {
    private val weekend = EventEntity(
        id = "e", ownerMemberId = "m", title = "Away", onDate = "2026-10-23",
        startMinute = 18 * 60, endMinute = 20 * 60, endDays = 2, createdAt = 1, updatedAt = 1,
    )
    private val gym = weekend.copy(onDate = null, weekdays = 1, endMinute = 19 * 60, endDays = 0)

    private fun persistent(event: EventEntity, lead: Int? = null, eachDay: Boolean = false) = ReminderView(
        ReminderWithDetails(
            ReminderEntity("r", "m", "", ReminderMode.PERSISTENT, 0, createdAt = 1, updatedAt = 1, eventId = event.id, leadMinutes = lead, eachDay = eachDay),
            emptyList(), emptyList(), emptyList(),
        ),
        "m",
        event,
    ).toSpec()

    @Test
    fun `each day is an all-day window on every day of the event`() {
        val spec = persistent(weekend, eachDay = true)
        assertEquals(listOf(Window(0, MINUTES_PER_DAY)), spec.windows)
        assertFalse(spec.wholeEvent)
        assertEquals(2, spec.eventEndDays)
    }

    @Test
    fun `whole event is one span, from midnight or from the lead`() {
        val allDay = persistent(weekend)
        assertTrue(allDay.windows.isEmpty())
        assertTrue(allDay.wholeEvent)
        assertEquals(2, allDay.eventEndDays)
        val lead = persistent(weekend, lead = 30)
        assertTrue(lead.wholeEvent)
        assertEquals(30, lead.leadMinutes)
        assertEquals(20 * 60, lead.eventEnd)
    }

    @Test
    fun `a one-day event is as before`() {
        val allDay = persistent(gym)
        assertEquals(listOf(Window(0, MINUTES_PER_DAY)), allDay.windows)
        assertFalse(allDay.wholeEvent)
        assertEquals(0, allDay.eventEndDays)
        // Each day left over from when it was multi-day: still just all day.
        assertEquals(listOf(Window(0, MINUTES_PER_DAY)), persistent(gym, eachDay = true).windows)
        val lead = persistent(gym, lead = 15)
        assertTrue(lead.windows.isEmpty())
        assertFalse(lead.wholeEvent)
        assertEquals(15, lead.leadMinutes)
    }
}
