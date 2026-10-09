package app.everyday.android.data

import app.everyday.android.reminders.Window
import org.junit.Assert.assertEquals
import org.junit.Test

class EventConversionTest {
    private val r102 = "00000000-0000-0000-0000-000000000102"
    private val sam = "00000000-0000-0000-0000-0000000000aa"
    private val alex = "00000000-0000-0000-0000-0000000000bb"

    @Test
    fun `derived ids match the server's md5 uuids`() {
        // Same values as the server's V7 MigrationTest.
        assertEquals("270e5aed-f735-7f94-f429-68262aadf0d2", derivedId(r102, "event:1"))
        assertEquals("5f38c583-6b68-27c5-62b6-d21fab68a217", derivedId(r102, "reminder:1"))
        assertEquals("bd44097f-5b70-3750-1fdb-29823e78cc46", derivedId(r102, "member:$alex:0"))
        assertEquals("96e242e3-560d-0a09-6a8c-8ae9474fcca4", derivedId(r102, "member:$alex:1"))
    }

    @Test
    fun `a shared persistent reminder with two windows becomes two shared events`() {
        val old = EventConversion.OldReminder(
            id = r102, ownerMemberId = sam, message = "Homework", mode = ReminderMode.PERSISTENT, weekdays = 31, active = true,
            ownerReceives = true, createdAt = 1, updatedAt = 2, dirty = false, times = emptyList(),
            windows = listOf(Window(1080, 1140), Window(960, 1020)), accepted = listOf(alex),
            states = listOf(ReminderStateEntity(r102, sam, doneOn = "2026-10-09"), ReminderStateEntity(r102, alex, doneOn = "2026-10-08")),
        )
        val out = EventConversion.convert(listOf(old), emptySet())
        val event1 = "270e5aed-f735-7f94-f429-68262aadf0d2"
        assertEquals(listOf(r102 to (960 to 1020), event1 to (1080 to 1140)), out.events.map { it.id to (it.startMinute to it.endMinute) })
        assertEquals(listOf(r102 to alex, event1 to alex), out.members.map { it.eventId to it.memberId })
        assertEquals(listOf(Triple(r102, r102, 0)), out.updated.map { Triple(it.id, it.eventId, it.leadMinutes) })
        assertEquals(
            listOf(
                "bd44097f-5b70-3750-1fdb-29823e78cc46" to r102,
                "5f38c583-6b68-27c5-62b6-d21fab68a217" to event1,
                "96e242e3-560d-0a09-6a8c-8ae9474fcca4" to event1,
            ).toSet(),
            out.inserted.map { it.id to it.eventId }.toSet(),
        )
        assertEquals(
            setOf(
                "5f38c583-6b68-27c5-62b6-d21fab68a217" to "2026-10-09",
                "bd44097f-5b70-3750-1fdb-29823e78cc46" to "2026-10-08",
                "96e242e3-560d-0a09-6a8c-8ae9474fcca4" to "2026-10-08",
            ),
            out.states.map { it.reminderId to it.doneOn }.toSet(),
        )
        assertEquals(listOf(r102 to alex), out.deletedStates)
    }

    @Test
    fun `a timed reminder becomes an all-day event, and an FYI keeps no owner reminder`() {
        val old = EventConversion.OldReminder(
            id = "r", ownerMemberId = sam, message = "Out tonight", mode = ReminderMode.TIMED, weekdays = 16, active = false,
            ownerReceives = false, createdAt = 1, updatedAt = 2, dirty = true, times = listOf(1080), windows = emptyList(),
            accepted = listOf(alex), states = emptyList(),
        )
        val out = EventConversion.convert(listOf(old), emptySet())
        val e = out.events.single()
        assertEquals(listOf(null, null, 16, true), listOf(e.startMinute, e.endMinute, e.weekdays, e.dirty))
        assertEquals(true, out.updated.single().deleted)
        val copy = out.inserted.single()
        assertEquals(listOf(alex, false, false), listOf(copy.ownerMemberId, copy.active, copy.dirty))
        assertEquals(listOf(1080), out.times.filter { it.reminderId == copy.id }.map { it.minuteOfDay })
    }
}
