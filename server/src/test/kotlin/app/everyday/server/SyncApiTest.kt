package app.everyday.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncApiTest {
    private lateinit var api: ApiContext
    private val db get() = ApiTest.postgres.postgresDatabase

    @BeforeTest
    fun setUp() {
        db.connection.use { it.createStatement().execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;") }
        migrate(db)
        api = ApiContext(HouseholdStore(db), ChoreStore(db), SyncStore(db), Secrets(iterations = 1_000), AttemptLimiter(), setupToken = "setup")
    }

    /** A household with Sam (admin), Alex and Kim, each with their own approved phone. */
    private class Home(val token: String, val sam: String, val alex: String, val kim: String, val tokens: Map<String, String>) {
        fun tokenOf(memberId: String) = tokens[memberId] ?: token
    }

    private fun api(block: suspend ApplicationTestBuilder.(HttpClient, Home) -> Unit) = testApplication {
        application { module(api) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val session = client.post("/household") {
            contentType(ContentType.Application.Json)
            setBody(CreateHouseholdRequest("setup", "Home", "kitchen-table", "Europe/London", "Sam", "4821"))
        }.body<SessionResponse>()
        suspend fun phoneFor(name: String): Pair<String, String> {
            val token = client.post("/join") {
                contentType(ContentType.Application.Json)
                setBody(JoinRequest("kitchen-table", name))
            }.body<SessionResponse>().token
            val requestId = client.get("/me") { header(HttpHeaders.Authorization, "Bearer ${session.token}") }
                .body<HouseholdResponse>().pendingRequestIds.single()
            val member = client.post("/join-requests/$requestId/approve") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer ${session.token}")
                header(ADMIN_PIN_HEADER, "4821")
                setBody(ApproveRequest())
            }.body<MemberDto>()
            return member.id to token
        }
        val sam = session.household.adminMemberId!!
        val (alex, alexToken) = phoneFor("Alex")
        val (kim, kimToken) = phoneFor("Kim")
        block(client, Home(session.token, sam, alex, kim, mapOf(sam to session.token, alex to alexToken, kim to kimToken)))
    }

    private suspend fun HttpClient.sync(home: Home, req: SyncRequest) = post("/v2/sync") {
        contentType(ContentType.Application.Json)
        header(HttpHeaders.Authorization, "Bearer ${home.tokenOf(req.memberId)}")
        setBody(req)
    }

    private suspend fun HttpClient.syncOk(home: Home, req: SyncRequest): SyncResponse {
        val response = sync(home, req)
        assertEquals(HttpStatusCode.OK, response.status)
        return response.body()
    }

    private fun event(owner: String, vararg members: String, updatedAt: Long = 1_000, id: String = UUID.randomUUID().toString()) =
        EventSyncDto(
            id = id,
            ownerMemberId = owner,
            title = "Swimming",
            weekdays = 0b0000010,
            startMinute = 17 * 60,
            endMinute = 18 * 60,
            members = members.map { EventMemberDto(it) },
            createdAt = 1_000,
            updatedAt = updatedAt,
        )

    private fun reminder(owner: String, eventId: String, updatedAt: Long = 1_000, id: String = UUID.randomUUID().toString()) =
        ReminderSyncDto(
            id = id,
            ownerMemberId = owner,
            eventId = eventId,
            mode = "TIMED",
            times = listOf(TimeDto(480), TimeDto(30, relative = true)),
            createdAt = 1_000,
            updatedAt = updatedAt,
        )

    @Test
    fun `the 1_1_0 sync route tells old phones to update`() = api { client, home ->
        val response = client.post("/sync") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${home.token}")
            setBody(SyncRequest(home.sam))
        }
        assertEquals(HttpStatusCode.Gone, response.status)
    }

    @Test
    fun `events and reminders push and come back in the snapshot`() = api { client, home ->
        val e = event(home.sam)
        val r = reminder(home.sam, e.id)
        val out = client.syncOk(home, SyncRequest(home.sam, events = listOf(e), reminders = listOf(r)))
        assertEquals(emptyList(), out.rejected)
        assertEquals(listOf(e), out.events)
        assertEquals(listOf(TimeDto(480), TimeDto(30, relative = true)), out.reminders.single().times)
        // Personal: nobody else sees it.
        val alex = client.syncOk(home, SyncRequest(home.alex))
        assertTrue(alex.events.isEmpty() && alex.reminders.isEmpty())
    }

    @Test
    fun `last write wins on events, and bad events are rejected on their own`() = api { client, home ->
        val e = event(home.sam, updatedAt = 2_000)
        client.syncOk(home, SyncRequest(home.sam, events = listOf(e)))
        assertEquals("Swimming", client.syncOk(home, SyncRequest(home.sam, events = listOf(e.copy(title = "Old", updatedAt = 1_500)))).events.single().title)
        assertEquals("Gym", client.syncOk(home, SyncRequest(home.sam, events = listOf(e.copy(title = "Gym", updatedAt = 3_000)))).events.single().title)

        val dated = event(home.sam).copy(weekdays = 0, onDate = "2026-10-20", startMinute = null, endMinute = null)
        val both = event(home.sam).copy(onDate = "2026-10-20")
        val neither = event(home.sam).copy(weekdays = 0)
        val endOnly = event(home.sam).copy(startMinute = null)
        val notMine = event(home.alex)
        val out = client.syncOk(home, SyncRequest(home.sam, events = listOf(dated, both, neither, endOnly, notMine)))
        assertEquals(listOf(both, neither, endOnly, notMine).map { "event:${it.id}" }, out.rejected)
        assertEquals("2026-10-20", out.events.single { it.id == dated.id }.onDate)
    }

    @Test
    fun `reminder rules`() = api { client, home ->
        val timed = event(home.sam)
        val allDay = event(home.sam).copy(startMinute = null, endMinute = null)
        client.syncOk(home, SyncRequest(home.sam, events = listOf(timed, allDay)))

        val noEvent = reminder(home.sam, timed.id).copy(eventId = null)
        val relativeOnAllDay = reminder(home.sam, allDay.id)
        val fixedOnAllDay = reminder(home.sam, allDay.id).copy(times = listOf(TimeDto(480)))
        val allDayPersistent = reminder(home.sam, allDay.id).copy(mode = "PERSISTENT", times = emptyList())
        val leadOnAllDay = allDayPersistent.copy(id = UUID.randomUUID().toString(), leadMinutes = 30)
        val lead = reminder(home.sam, timed.id).copy(mode = "PERSISTENT", times = emptyList(), leadMinutes = 30)
        val otherEvent = reminder(home.alex, timed.id)
        val out = client.syncOk(home, SyncRequest(home.sam, reminders = listOf(noEvent, relativeOnAllDay, fixedOnAllDay, allDayPersistent, leadOnAllDay, lead)))
        assertEquals(listOf(noEvent.id, relativeOnAllDay.id, leadOnAllDay.id), out.rejected)
        assertEquals(30, out.reminders.single { it.id == lead.id }.leadMinutes)
        // Alex can't put a reminder on Sam's personal event.
        assertEquals(listOf(otherEvent.id), client.syncOk(home, SyncRequest(home.alex, reminders = listOf(otherEvent))).rejected)
        // An older write loses.
        val stale = lead.copy(leadMinutes = 60, updatedAt = 500)
        assertEquals(30, client.syncOk(home, SyncRequest(home.sam, reminders = listOf(stale))).reminders.single { it.id == lead.id }.leadMinutes)
    }

    @Test
    fun `shared events show for their members, who see only their own reminders`() = api { client, home ->
        val e = event(home.sam, home.alex)
        client.syncOk(home, SyncRequest(home.sam, events = listOf(e), reminders = listOf(reminder(home.sam, e.id))))
        val alexEvent = client.syncOk(home, SyncRequest(home.alex)).events.single()
        assertEquals(listOf(home.alex), alexEvent.members.map { it.memberId })
        assertTrue(client.syncOk(home, SyncRequest(home.kim)).events.isEmpty())

        val alexReminder = reminder(home.alex, e.id)
        val alex = client.syncOk(home, SyncRequest(home.alex, reminders = listOf(alexReminder)))
        assertEquals(listOf(alexReminder.id), alex.reminders.map { it.id })
        assertEquals(1, client.syncOk(home, SyncRequest(home.sam)).reminders.size)
        // Only the owner edits the event.
        assertEquals(listOf("event:${e.id}"), client.syncOk(home, SyncRequest(home.alex, events = listOf(e.copy(title = "Mine", updatedAt = 9_000)))).rejected)
        // States only for your own reminders.
        assertEquals(
            listOf("state:${alexReminder.id}"),
            client.syncOk(home, SyncRequest(home.sam, states = listOf(StateSyncDto(alexReminder.id, doneOn = "2026-10-08", updatedAt = 2_000)))).rejected,
        )
    }

    @Test
    fun `leaving a shared event removes me and my reminders on it, until the owner adds me again`() = api { client, home ->
        val e = event(home.sam, home.alex, home.kim)
        client.syncOk(home, SyncRequest(home.sam, events = listOf(e)))
        val r = reminder(home.alex, e.id)
        client.syncOk(home, SyncRequest(home.alex, reminders = listOf(r)))

        val left = client.syncOk(home, SyncRequest(home.alex, leave = listOf(LeaveSyncDto(e.id, 2_000))))
        assertTrue(left.events.isEmpty())
        assertTrue(left.reminders.single().deleted)
        assertEquals(listOf(home.kim), client.syncOk(home, SyncRequest(home.sam)).events.single().members.map { it.memberId })
        val again = reminder(home.alex, e.id)
        assertEquals(listOf(again.id), client.syncOk(home, SyncRequest(home.alex, reminders = listOf(again))).rejected)
        // Someone it was never shared with can't leave it.
        val other = event(home.sam)
        client.syncOk(home, SyncRequest(home.sam, events = listOf(other)))
        assertEquals(listOf("leave:${other.id}"), client.syncOk(home, SyncRequest(home.kim, leave = listOf(LeaveSyncDto(other.id, 2_000)))).rejected)

        // A stale list from the owner's phone (Alex added at 1_000, before leaving at 2_000) doesn't add Alex back...
        val stale = e.copy(title = "Swim", updatedAt = 3_000, members = listOf(EventMemberDto(home.alex, 1_000), EventMemberDto(home.kim, 1_000)))
        client.syncOk(home, SyncRequest(home.sam, events = listOf(stale)))
        assertTrue(client.syncOk(home, SyncRequest(home.alex)).events.isEmpty())
        // ...but adding Alex again does.
        val readded = stale.copy(updatedAt = 4_000, members = listOf(EventMemberDto(home.alex, 4_000), EventMemberDto(home.kim, 1_000)))
        client.syncOk(home, SyncRequest(home.sam, events = listOf(readded)))
        assertEquals("Swim", client.syncOk(home, SyncRequest(home.alex)).events.single().title)
    }

    @Test
    fun `removing a member from an event, or deleting it, deletes their reminders on it`() = api { client, home ->
        val e = event(home.sam, home.alex, home.kim)
        client.syncOk(home, SyncRequest(home.sam, events = listOf(e)))
        val alexReminder = reminder(home.alex, e.id)
        val kimReminder = reminder(home.kim, e.id)
        client.syncOk(home, SyncRequest(home.alex, reminders = listOf(alexReminder)))
        client.syncOk(home, SyncRequest(home.kim, reminders = listOf(kimReminder)))

        client.syncOk(home, SyncRequest(home.sam, events = listOf(e.copy(members = listOf(EventMemberDto(home.kim)), updatedAt = 2_000))))
        val alex = client.syncOk(home, SyncRequest(home.alex))
        assertTrue(alex.events.isEmpty())
        assertTrue(alex.reminders.single().deleted)
        assertTrue(!client.syncOk(home, SyncRequest(home.kim)).reminders.single().deleted)

        client.syncOk(home, SyncRequest(home.sam, events = listOf(e.copy(deleted = true, updatedAt = 3_000))))
        val kim = client.syncOk(home, SyncRequest(home.kim))
        assertTrue(kim.events.single().deleted)
        assertTrue(kim.reminders.single().deleted)
    }

    @Test
    fun `state and settings follow last write wins`() = api { client, home ->
        val e = event(home.sam)
        val r = reminder(home.sam, e.id)
        client.syncOk(home, SyncRequest(home.sam, events = listOf(e), reminders = listOf(r)))
        client.syncOk(home, SyncRequest(home.sam, states = listOf(StateSyncDto(r.id, doneOn = "2026-10-08", updatedAt = 2_000))))
        val stale = client.syncOk(home, SyncRequest(home.sam, states = listOf(StateSyncDto(r.id, doneOn = null, updatedAt = 1_500))))
        assertEquals("2026-10-08", stale.states.single().doneOn)

        val settings = client.syncOk(home, SyncRequest(home.sam, settings = SettingsSyncDto(25, "SOUND_VIBRATE", "SOUND", 5_000))).settings!!
        assertEquals(listOf(25, "SOUND_VIBRATE", "SOUND"), listOf(settings.snoozeMinutes, settings.timedSound, settings.persistentSound))
        assertEquals(listOf("settings"), client.syncOk(home, SyncRequest(home.sam, settings = SettingsSyncDto(0, "LOUD", "SILENT", 6_000))).rejected)
    }

    @Test
    fun `chore reminders need the owner assigned`() = api { client, home ->
        val choreId = UUID.randomUUID().toString()
        client.put("/chores/$choreId") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${home.token}")
            setBody(ChoreRequest("Bins", weekdays = 127, completion = ChoreCompletion.ANY, assigneeIds = listOf(home.sam)))
        }
        val choreReminder = ReminderSyncDto(
            id = UUID.randomUUID().toString(), ownerMemberId = home.sam, choreId = choreId, message = "Bins", mode = "TIMED",
            weekdays = 127, times = listOf(TimeDto(480), TimeDto(1200)), createdAt = 1_000, updatedAt = 1_000,
        )
        val relative = choreReminder.copy(id = UUID.randomUUID().toString(), times = listOf(TimeDto(30, relative = true)))
        val unassigned = choreReminder.copy(id = UUID.randomUUID().toString(), ownerMemberId = home.alex)
        assertEquals(listOf(relative.id), client.syncOk(home, SyncRequest(home.sam, reminders = listOf(choreReminder, relative))).rejected)
        assertEquals(listOf(unassigned.id), client.syncOk(home, SyncRequest(home.alex, reminders = listOf(unassigned))).rejected)

        // The chore timetable shows the assignee's reminder times.
        val chores = client.get("/chores") { header(HttpHeaders.Authorization, "Bearer ${home.token}") }.body<ChoresResponse>()
        assertEquals(listOf(480, 1200), chores.slots.filter { it.memberId == home.sam }.map { it.startMinute })
        // Paused reminders don't show.
        client.syncOk(home, SyncRequest(home.sam, reminders = listOf(choreReminder.copy(active = false, updatedAt = 2_000))))
        assertTrue(client.get("/chores") { header(HttpHeaders.Authorization, "Bearer ${home.token}") }.body<ChoresResponse>().slots.isEmpty())
    }

    @Test
    fun `a removed member's shared event lives on until every member leaves it`() = api { client, home ->
        val personal = event(home.alex)
        val shared = event(home.alex, home.sam, home.kim)
        client.syncOk(home, SyncRequest(home.alex, events = listOf(personal, shared), reminders = listOf(reminder(home.alex, shared.id))))
        val samsShared = event(home.sam, home.alex)
        client.syncOk(home, SyncRequest(home.sam, events = listOf(samsShared)))
        val samReminder = reminder(home.sam, shared.id)
        client.syncOk(home, SyncRequest(home.sam, reminders = listOf(samReminder)))

        client.delete("/members/${home.alex}") {
            header(HttpHeaders.Authorization, "Bearer ${home.token}")
            header(ADMIN_PIN_HEADER, "4821")
        }

        val sam = client.syncOk(home, SyncRequest(home.sam))
        assertTrue(!sam.events.single { it.id == shared.id }.deleted)
        assertTrue(sam.events.single { it.id == samsShared.id }.members.isEmpty())

        // Sam and Kim leave it; then it's deleted.
        client.syncOk(home, SyncRequest(home.sam, leave = listOf(LeaveSyncDto(shared.id, 3_000))))
        assertNull(client.syncOk(home, SyncRequest(home.sam)).reminders.firstOrNull { it.id == samReminder.id && !it.deleted })
        assertTrue(client.syncOk(home, SyncRequest(home.kim)).events.isNotEmpty())
        client.syncOk(home, SyncRequest(home.kim, leave = listOf(LeaveSyncDto(shared.id, 3_000))))
        val orphan = db.connection.use { conn ->
            conn.createStatement().executeQuery("SELECT deleted FROM event WHERE id = '${shared.id}'").use { it.next(); it.getBoolean(1) }
        }
        assertTrue(orphan)
    }
}
