package app.everyday.android.sync

import app.everyday.android.data.EventEntity
import app.everyday.android.data.EventMemberEntity
import app.everyday.android.data.EventWithMembers
import app.everyday.android.data.MemberSettingsEntity
import app.everyday.android.data.ReminderEntity
import app.everyday.android.data.ReminderMode
import app.everyday.android.data.ReminderStateEntity
import app.everyday.android.data.ReminderWithDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergeTest {
    private val me = "sam"

    private fun entity(id: String, owner: String = me, updatedAt: Long = 100, dirty: Boolean = false, message: String = "m") =
        ReminderEntity(id, owner, message, ReminderMode.TIMED, 127, createdAt = 1, updatedAt = updatedAt, dirty = dirty)

    private fun local(e: ReminderEntity) = ReminderWithDetails(e, emptyList(), emptyList(), emptyList())

    private fun server(e: ReminderEntity) = SyncedReminder(e, listOf(SyncedTime(480)), emptyList())

    private fun event(id: String, owner: String = me, updatedAt: Long = 100, dirty: Boolean = false, title: String = "e") =
        EventEntity(id, owner, title, weekdays = 127, createdAt = 1, updatedAt = updatedAt, dirty = dirty)

    private fun snapshot(
        vararg r: SyncedReminder,
        events: List<SyncedEvent> = emptyList(),
        states: List<ReminderStateEntity> = emptyList(),
        settings: MemberSettingsEntity? = null,
        rejected: Set<String> = emptySet(),
    ) = SyncSnapshot(events, r.toList(), states, settings, rejected)

    private fun plan(
        local: List<ReminderWithDetails>,
        server: SyncSnapshot,
        pushed: Pushed = Pushed(),
        states: List<ReminderStateEntity> = emptyList(),
        settings: MemberSettingsEntity? = null,
        events: List<EventWithMembers> = emptyList(),
    ) = SyncMerge.plan(me, events, local, states, settings, pushed, server)

    @Test
    fun `server version is saved clean`() {
        val p = plan(listOf(local(entity("a", dirty = true, updatedAt = 200))), snapshot(server(entity("a", updatedAt = 200, message = "srv"))), Pushed(reminders = mapOf("a" to 200)))
        val saved = p.saveReminders.single()
        assertEquals("srv", saved.reminder.message)
        assertFalse(saved.reminder.dirty)
    }

    @Test
    fun `an edit made during the sync is kept and stays dirty`() {
        // Pushed at 200, edited again to 300 before the answer came back.
        val p = plan(listOf(local(entity("a", dirty = true, updatedAt = 300))), snapshot(server(entity("a", updatedAt = 200))), Pushed(reminders = mapOf("a" to 200)))
        assertTrue(p.saveReminders.isEmpty())
        assertTrue(p.clearDirty.isEmpty())
    }

    @Test
    fun `a newer server version replaces a clean local row`() {
        val p = plan(listOf(local(entity("a", updatedAt = 100))), snapshot(server(entity("a", updatedAt = 500, message = "other phone"))))
        assertEquals("other phone", p.saveReminders.single().reminder.message)
    }

    @Test
    fun `absence never deletes my own reminders`() {
        val p = plan(listOf(local(entity("mine"))), snapshot())
        assertTrue(p.deleteLocal.isEmpty())
        assertTrue(p.saveReminders.isEmpty())
    }

    @Test
    fun `reminders that aren't mine are removed from this phone`() {
        assertEquals(listOf("s"), plan(listOf(local(entity("s", owner = "alex"))), snapshot()).deleteLocal)
    }

    @Test
    fun `tombstones from the server are saved`() {
        val p = plan(listOf(local(entity("a"))), snapshot(server(entity("a", updatedAt = 500).copy(deleted = true))))
        assertTrue(p.saveReminders.single().reminder.deleted)
    }

    @Test
    fun `a refused row the server doesn't have is kept but marked clean`() {
        val p = plan(
            listOf(local(entity("bad", dirty = true, updatedAt = 200))),
            snapshot(rejected = setOf("bad")),
            Pushed(reminders = mapOf("bad" to 200)),
        )
        assertEquals(listOf("bad"), p.clearDirty)
        assertTrue(p.deleteLocal.isEmpty())
    }

    @Test
    fun `events follow last write wins, and shared events that are gone are removed`() {
        val mine = EventWithMembers(event("mine", dirty = true, updatedAt = 300), emptyList())
        val shared = EventWithMembers(event("shared", owner = "alex"), listOf(EventMemberEntity("shared", me)))
        val unshared = EventWithMembers(event("unshared", owner = "alex"), listOf(EventMemberEntity("unshared", me)))
        val p = plan(
            emptyList(),
            snapshot(events = listOf(SyncedEvent(event("mine", updatedAt = 200), emptyList()), SyncedEvent(event("shared", owner = "alex", title = "new"), emptyList()))),
            Pushed(events = mapOf("mine" to 200)),
            events = listOf(mine, shared, unshared),
        )
        // "mine" was edited again during the sync, so the server's copy is skipped.
        assertEquals(listOf("new"), p.saveEvents.map { it.event.title })
        assertEquals(listOf("unshared"), p.deleteEvents)
        // My own events are never removed for being missing.
        assertTrue(plan(emptyList(), snapshot(), events = listOf(mine)).deleteEvents.isEmpty())
    }

    @Test
    fun `leaving an event during the sync survives the server's copy`() {
        val mine = EventMemberEntity("s", me, hidden = true, updatedAt = 300, dirty = true)
        val srv = SyncedEvent(event("s", owner = "alex"), listOf(EventMemberEntity("s", me, updatedAt = 200), EventMemberEntity("s", "kim")))
        val p = plan(
            emptyList(),
            snapshot(events = listOf(srv)),
            Pushed(leaves = mapOf("s" to 200)),
            events = listOf(EventWithMembers(event("s", owner = "alex"), listOf(mine))),
        )
        val members = p.saveEvents.single().members.associateBy { it.memberId }
        assertTrue(members.getValue(me).hidden)
        assertTrue(members.getValue(me).dirty)
        assertFalse(members.getValue("kim").dirty)
    }

    @Test
    fun `states follow the same rules and skip reminders not on this phone`() {
        val pushedState = ReminderStateEntity("a", me, doneOn = "2026-10-08", updatedAt = 200, dirty = true)
        val editedAgain = ReminderStateEntity("b", me, doneOn = null, updatedAt = 300, dirty = true)
        val p = plan(
            listOf(local(entity("a")), local(entity("b"))),
            snapshot(
                states = listOf(
                    ReminderStateEntity("a", me, doneOn = "2026-10-08", updatedAt = 200),
                    ReminderStateEntity("b", me, doneOn = "2026-10-08", updatedAt = 200),
                    ReminderStateEntity("gone", me, updatedAt = 1),
                ),
            ),
            Pushed(states = mapOf("a" to 200, "b" to 200)),
            states = listOf(pushedState, editedAgain),
        )
        assertEquals(listOf("a"), p.saveStates.map { it.reminderId })
        assertFalse(p.saveStates.single().dirty)
    }

    @Test
    fun `settings edited during the sync are kept`() {
        val localSettings = MemberSettingsEntity(me, snoozeMinutes = 20, updatedAt = 300, dirty = true)
        val p = plan(emptyList(), snapshot(settings = MemberSettingsEntity(me, snoozeMinutes = 15, updatedAt = 200)), Pushed(settings = 200), settings = localSettings)
        assertNull(p.saveSettings)
        val clean = plan(emptyList(), snapshot(settings = MemberSettingsEntity(me, snoozeMinutes = 15, updatedAt = 200)), Pushed(settings = 200), settings = localSettings.copy(updatedAt = 200))
        assertEquals(15, clean.saveSettings!!.snoozeMinutes)
    }
}
