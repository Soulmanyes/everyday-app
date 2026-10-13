package app.everyday.android.calendarimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class ImportPlannerTest {
    private val london = ZoneId.of("Europe/London")
    // Wednesday.
    private val today = LocalDate.of(2026, 10, 14)

    private fun ms(text: String) = LocalDateTime.parse(text).atZone(london).toInstant().toEpochMilli()
    private fun utc(date: String) = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun timed(id: Long, start: String, end: String, rrule: String? = null, uid: String? = "uid$id", title: String = "Event $id") =
        PhoneInstance(id, 1, uid, title, null, allDay = false, begin = ms(start), end = ms(end), rrule = rrule)

    private fun allDay(id: Long, start: String, endExclusive: String, rrule: String? = null) =
        PhoneInstance(id, 1, "uid$id", "Day $id", null, allDay = true, begin = utc(start), end = utc(endExclusive), rrule = rrule)

    private fun plan(vararg list: PhoneInstance, imported: Set<String> = emptySet()) =
        ImportPlanner.plan(list.toList(), today, london, imported)

    @Test
    fun `a single timed event becomes one one-off event`() {
        val c = plan(timed(1, "2026-10-20T09:00", "2026-10-20T10:30")).single()
        val d = c.items.single().draft
        assertFalse(c.weekly)
        assertEquals("uid1", c.items.single().sourceKey)
        assertFalse(d.weekly)
        assertEquals(LocalDate.of(2026, 10, 20), d.date)
        assertEquals(9 * 60, d.startMinute)
        assertEquals(10 * 60 + 30, d.endMinute)
        assertEquals(0, d.endDays)
    }

    @Test
    fun `a weekly repeat without an end becomes one weekly event`() {
        val rule = "FREQ=WEEKLY;BYDAY=MO,TH;WKST=MO"
        val c = plan(
            timed(1, "2026-10-15T18:00", "2026-10-15T19:00", rule),
            timed(1, "2026-10-19T18:00", "2026-10-19T19:00", rule),
            timed(1, "2026-10-22T18:00", "2026-10-22T19:00", rule),
        ).single()
        assertTrue(c.weekly)
        val d = c.items.single().draft
        assertTrue(d.weekly)
        assertEquals(0b0001001, d.weekdays)
        assertEquals("uid1", c.items.single().sourceKey)
    }

    @Test
    fun `a daily repeat without an end becomes every day`() {
        val rule = "FREQ=DAILY"
        val days = (14..27).map { timed(1, "2026-10-%02dT07:00".format(it), "2026-10-%02dT07:30".format(it), rule) }
        val d = plan(*days.toTypedArray()).single().items.single().draft
        assertTrue(d.weekly)
        assertEquals(0b1111111, d.weekdays)
    }

    @Test
    fun `repeats with an end, an interval or other rules become one-off events`() {
        for (rule in listOf("FREQ=WEEKLY;COUNT=2", "FREQ=WEEKLY;UNTIL=20261231T000000Z", "FREQ=WEEKLY;INTERVAL=2", "FREQ=MONTHLY", "FREQ=DAILY;INTERVAL=3")) {
            val c = plan(
                timed(1, "2026-10-15T18:00", "2026-10-15T19:00", rule),
                timed(1, "2026-10-29T18:00", "2026-10-29T19:00", rule),
            ).single()
            assertFalse(rule, c.weekly)
            assertEquals(rule, listOf("uid1|start=${ms("2026-10-15T18:00")}", "uid1|start=${ms("2026-10-29T18:00")}"), c.items.map { it.sourceKey })
        }
    }

    @Test
    fun `a weekly series whose occurrence today is over stays weekly`() {
        // Wednesday 09:00, imported Wednesday afternoon: the first occurrence listed is next week's.
        val rule = "FREQ=WEEKLY;BYDAY=WE"
        val next = timed(1, "2026-10-21T09:00", "2026-10-21T10:00", rule).copy(seriesStart = ms("2026-09-02T09:00"))
        val c = plan(next, next.copy(begin = ms("2026-10-28T09:00"), end = ms("2026-10-28T10:00"))).single()
        assertTrue(c.weekly)
        assertEquals(0b0000100, c.items.single().draft.weekdays)
    }

    @Test
    fun `the same event in two calendars is listed once`() {
        val a = timed(1, "2026-10-20T09:00", "2026-10-20T10:00", uid = "x@google.com")
        val b = a.copy(eventId = 2, calendarId = 2)
        assertEquals(1, plan(a, b).size)
    }

    @Test
    fun `a series imported one way is known the other way too`() {
        val rule = "FREQ=WEEKLY"
        val w = timed(1, "2026-10-15T18:00", "2026-10-15T19:00", rule, uid = "f@google.com")
        // Imported before as one-off occurrences, now weekly: already imported.
        assertTrue(plan(w, imported = setOf("f@google.com|start=1")).single().alreadyImported)
        // Imported before as weekly, now one-offs (every 2 weeks reads as one-offs): already imported.
        val o = timed(1, "2026-10-15T18:00", "2026-10-15T19:00", "FREQ=WEEKLY;INTERVAL=2", uid = "f@google.com")
        assertTrue(plan(o, imported = setOf("f@google.com")).single().alreadyImported)
    }

    @Test
    fun `a weekly repeat starting more than a week ahead becomes one-off events`() {
        val rule = "FREQ=WEEKLY"
        val c = plan(timed(1, "2026-10-28T18:00", "2026-10-28T19:00", rule), timed(1, "2026-11-04T18:00", "2026-11-04T19:00", rule)).single()
        assertFalse(c.weekly)
        assertEquals(2, c.items.size)
    }

    @Test
    fun `a weekly repeat whose times change (clock change in another zone) becomes one-off events`() {
        val rule = "FREQ=WEEKLY"
        val c = plan(timed(1, "2026-10-15T18:00", "2026-10-15T19:00", rule), timed(1, "2026-10-22T17:00", "2026-10-22T18:00", rule)).single()
        assertFalse(c.weekly)
    }

    @Test
    fun `all-day events use UTC dates with an exclusive end`() {
        val one = plan(allDay(1, "2026-10-20", "2026-10-21")).single().items.single().draft
        assertEquals(LocalDate.of(2026, 10, 20), one.date)
        assertNull(one.startMinute)
        assertEquals(0, one.endDays)
        val three = plan(allDay(2, "2026-10-20", "2026-10-23")).single().items.single().draft
        assertEquals(2, three.endDays)
    }

    @Test
    fun `timed events over midnight become multi-day events`() {
        val d = plan(timed(1, "2026-10-20T22:00", "2026-10-22T02:00")).single().items.single().draft
        assertEquals(22 * 60, d.startMinute)
        assertEquals(2, d.endDays)
        assertEquals(2 * 60, d.endMinute)
        // Ending at midnight is the end of the first day.
        val toMidnight = plan(timed(2, "2026-10-20T20:00", "2026-10-21T00:00")).single().items.single().draft
        assertEquals(0, toMidnight.endDays)
        assertEquals(1440, toMidnight.endMinute)
    }

    @Test
    fun `a weekly multi-day event on two weekdays can't be weekly`() {
        val rule = "FREQ=WEEKLY;BYDAY=MO,TH"
        val c = plan(
            timed(1, "2026-10-15T22:00", "2026-10-16T02:00", rule),
            timed(1, "2026-10-19T22:00", "2026-10-20T02:00", rule),
        ).single()
        assertFalse(c.weekly)
        assertEquals(2, c.items.size)
    }

    @Test
    fun `events imported before are left out, and a fully imported one says so`() {
        val single = timed(1, "2026-10-20T09:00", "2026-10-20T10:00")
        val rule = "FREQ=MONTHLY"
        val a = timed(2, "2026-10-21T09:00", "2026-10-21T10:00", rule)
        val b = timed(2, "2026-11-21T09:00", "2026-11-21T10:00", rule)
        val out = plan(single, a, b, imported = setOf("uid1", "uid2|start=${a.begin}"))
        assertTrue(out[0].alreadyImported)
        assertEquals(listOf("uid2|start=${b.begin}"), out[1].items.map { it.sourceKey })
        assertEquals(1, out[1].importedBefore)
    }

    @Test
    fun `no UID falls back to the phone's id, and a blank title gets one`() {
        val c = plan(timed(7, "2026-10-20T09:00", "2026-10-20T10:00", uid = null, title = "  ")).single()
        assertEquals("id:7", c.items.single().sourceKey)
        assertEquals("(No title)", c.title)
    }

    @Test
    fun `reminders count back from the start, or become a time on the day for all-day events`() {
        val choices = plan(timed(1, "2026-10-20T09:00", "2026-10-20T10:00"), allDay(2, "2026-10-21", "2026-10-22"))
        val offers = ImportPlanner.offers(choices) { if (it == 1L) listOf(30, 1440, 30, 20160) else listOf(540, 0) }
        val timed = offers.filter { it.eventId == 1L }
        assertEquals(listOf(30, 1440, 20160), timed.map { it.minute })
        assertTrue(timed.all { it.relative })
        assertEquals(listOf(false, false, true), timed.map { it.tooEarly })
        val day = offers.filter { it.eventId == 2L }
        // 540 minutes before midnight = 15:00 the day before, offered as 15:00 on the day.
        assertEquals(listOf(0, 15 * 60), day.map { it.minute })
        assertTrue(day.none { it.relative })
    }

    @Test
    fun `notes lose Google's HTML and are cut to 500`() {
        assertEquals("Bring <b>\nboots & coat", ImportPlanner.cleanNotes("Bring &lt;b&gt;<br>boots &amp; <i>coat</i>"))
        assertEquals("", ImportPlanner.cleanNotes(null))
        assertEquals(500, ImportPlanner.cleanNotes("x".repeat(900)).length)
    }

    @Test
    fun `open-ended weekly rules`() {
        assertTrue(ImportPlanner.openEndedWeekly("FREQ=WEEKLY;WKST=SU;BYDAY=TU"))
        assertTrue(ImportPlanner.openEndedWeekly("RRULE:FREQ=DAILY;INTERVAL=1"))
        assertFalse(ImportPlanner.openEndedWeekly("FREQ=WEEKLY;BYDAY=1MO"))
        assertFalse(ImportPlanner.openEndedWeekly("FREQ=YEARLY"))
        assertFalse(ImportPlanner.openEndedWeekly("FREQ=WEEKLY;BYMONTH=6"))
    }
}
