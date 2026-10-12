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
            setBody(CreateHouseholdRequest("Home", "Europe/London", "Sam", "4821", setupToken = "setup"))
        }.body<SessionResponse>()
        suspend fun phoneFor(name: String): Pair<String, String> {
            val token = client.post("/join") {
                contentType(ContentType.Application.Json)
                setBody(JoinRequest(session.joinCode, name))
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
            endDays = 0,
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
            eachDay = false,
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
        // A new event shows as Busy unless the phone says otherwise.
        assertEquals(listOf(e.copy(showBusy = true, schedulingMembers = emptyList(), everyone = Everyone.NONE)), out.events)
        assertEquals(listOf(TimeDto(480), TimeDto(30, relative = true)), out.reminders.single().times)
        // Personal: nobody else sees it in full (only as Busy).
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

    @Test
    fun `personal events show to others as Busy, without title or notes, unless the owner hides them`() = api { client, home ->
        val gym = event(home.alex).copy(notes = "Leg day")
        val hidden = event(home.alex).copy(showBusy = false)
        val shared = event(home.alex, home.kim)
        client.syncOk(home, SyncRequest(home.alex, events = listOf(gym, hidden, shared)))

        // Sam sees only the personal, visible one, as days and times.
        val sam = client.syncOk(home, SyncRequest(home.sam))
        assertEquals(listOf(BusyDto(gym.id, home.alex, gym.weekdays, null, gym.startMinute, gym.endMinute)), sam.busy)
        assertTrue(sam.events.isEmpty())
        // Kim is on the shared one and sees it in full; it's not Busy for anyone.
        val kim = client.syncOk(home, SyncRequest(home.kim))
        assertEquals(listOf(gym.id), kim.busy.map { it.id })
        // The owner gets the setting back on their own events, and never sees their own as Busy.
        val alex = client.syncOk(home, SyncRequest(home.alex))
        assertTrue(alex.busy.isEmpty())
        assertEquals(false, alex.events.single { it.id == hidden.id }.showBusy)

        // A phone before 1.4.0 sends no showBusy: the stored value stays.
        client.syncOk(home, SyncRequest(home.alex, events = listOf(hidden.copy(title = "Renamed", showBusy = null, updatedAt = 2_000))))
        assertEquals(false, client.syncOk(home, SyncRequest(home.alex)).events.single { it.id == hidden.id }.showBusy)
        // Turning it on shows it; deleting it removes it.
        client.syncOk(home, SyncRequest(home.alex, events = listOf(hidden.copy(showBusy = true, updatedAt = 3_000))))
        assertEquals(setOf(gym.id, hidden.id), client.syncOk(home, SyncRequest(home.sam)).busy.map { it.id }.toSet())
        client.syncOk(home, SyncRequest(home.alex, events = listOf(gym.copy(deleted = true, updatedAt = 4_000))))
        assertEquals(listOf(hidden.id), client.syncOk(home, SyncRequest(home.sam)).busy.map { it.id })

        // A shared event from a phone before 2.4 never shows as Busy, even once everyone has left it.
        client.syncOk(home, SyncRequest(home.kim, leave = listOf(LeaveSyncDto(shared.id, 5_000))))
        assertEquals(listOf(hidden.id), client.syncOk(home, SyncRequest(home.sam)).busy.map { it.id })
    }

    /** An event from a 2.4 phone: [members] take part, [scheduling] see it for scheduling. */
    private fun shared(
        owner: String,
        members: List<String> = emptyList(),
        scheduling: List<String> = emptyList(),
        everyone: String = Everyone.NONE,
        showBusy: Boolean = false,
        updatedAt: Long = 1_000,
    ) = event(owner, *members.toTypedArray(), updatedAt = updatedAt).copy(
        schedulingMembers = scheduling.map { EventMemberDto(it) },
        everyone = everyone,
        showBusy = showBusy,
        notes = "Secret",
    )

    /** A fourth member with a phone, as if they joined after the event was made. */
    private suspend fun HttpClient.newMember(home: Home, name: String): Pair<String, String> {
        val token = UUID.randomUUID().toString()
        val householdId = get("/me") { header(HttpHeaders.Authorization, "Bearer ${home.token}") }.body<HouseholdResponse>().household.id
        api.store.addPendingDevice(UUID.fromString(householdId), Secrets.tokenHash(token), "phone", name)
        val requestId = get("/me") { header(HttpHeaders.Authorization, "Bearer ${home.token}") }.body<HouseholdResponse>().pendingRequestIds.single()
        val member = post("/join-requests/$requestId/approve") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${home.token}")
            header(ADMIN_PIN_HEADER, "4821")
            setBody(ApproveRequest())
        }.body<MemberDto>()
        return member.id to token
    }

    @Test
    fun `an Everyone event shows to every member, including ones who join later, until they leave it`() = api { client, home ->
        val e = shared(home.sam, everyone = Everyone.MEMBER)
        client.syncOk(home, SyncRequest(home.sam, events = listOf(e)))
        val alex = client.syncOk(home, SyncRequest(home.alex)).events.single()
        assertEquals(listOf(e.id, Everyone.MEMBER), listOf(alex.id, alex.everyone))
        assertTrue(alex.members.isEmpty())
        val r = reminder(home.alex, e.id)
        assertTrue(client.syncOk(home, SyncRequest(home.alex, reminders = listOf(r))).rejected.isEmpty())
        assertEquals(Everyone.MEMBER, client.syncOk(home, SyncRequest(home.sam)).events.single().everyone)

        val (jo, joToken) = client.newMember(home, "Jo")
        val joHome = Home(home.token, home.sam, home.alex, home.kim, home.tokens + (jo to joToken))
        assertEquals(listOf(e.id), client.syncOk(joHome, SyncRequest(jo)).events.map { it.id })

        // Leaving removes Alex and their reminders; an edit from the owner doesn't bring it back.
        val left = client.syncOk(home, SyncRequest(home.alex, leave = listOf(LeaveSyncDto(e.id, 2_000))))
        assertTrue(left.events.isEmpty())
        assertTrue(left.reminders.single().deleted)
        assertTrue(left.busy.isEmpty())
        client.syncOk(home, SyncRequest(home.sam, events = listOf(e.copy(title = "Swim", updatedAt = 3_000))))
        assertTrue(client.syncOk(home, SyncRequest(home.alex)).events.isEmpty())
        assertEquals("Swim", client.syncOk(home, SyncRequest(home.kim)).events.single().title)
        // Leaving twice changes nothing.
        assertTrue(client.syncOk(home, SyncRequest(home.alex, leave = listOf(LeaveSyncDto(e.id, 4_000)))).rejected.isEmpty())
        // The owner adding Alex by name brings it back.
        client.syncOk(home, SyncRequest(home.sam, events = listOf(e.copy(updatedAt = 5_000, members = listOf(EventMemberDto(home.alex, 5_000))))))
        assertEquals(listOf(e.id), client.syncOk(home, SyncRequest(home.alex)).events.map { it.id })
        // Taking Alex off the list again leaves Everyone in place for them (they didn't leave).
        client.syncOk(home, SyncRequest(home.sam, events = listOf(e.copy(updatedAt = 6_000))))
        assertEquals(listOf(e.id), client.syncOk(home, SyncRequest(home.alex)).events.map { it.id })
    }

    @Test
    fun `events shared for scheduling show their title and times only, and can be hidden`() = api { client, home ->
        val e = shared(home.sam, members = listOf(home.alex), scheduling = listOf(home.kim))
        client.syncOk(home, SyncRequest(home.sam, events = listOf(e)))
        val owner = client.syncOk(home, SyncRequest(home.sam)).events.single()
        assertEquals(listOf(home.alex), owner.members.map { it.memberId })
        assertEquals(listOf(home.kim), owner.schedulingMembers!!.map { it.memberId })

        val kim = client.syncOk(home, SyncRequest(home.kim))
        assertTrue(kim.events.isEmpty())
        assertEquals(listOf(BusyDto(e.id, home.sam, e.weekdays, null, e.startMinute, e.endMinute, title = "Swimming")), kim.busy)
        // No reminders for scheduling.
        val r = reminder(home.kim, e.id)
        assertEquals(listOf(r.id), client.syncOk(home, SyncRequest(home.kim, reminders = listOf(r))).rejected)
        assertEquals(listOf(e.id), client.syncOk(home, SyncRequest(home.alex)).events.map { it.id })

        // Hiding it: gone for Kim, also as Busy, until the owner shares it again.
        val hidden = client.syncOk(home, SyncRequest(home.kim, leave = listOf(LeaveSyncDto(e.id, 2_000))))
        assertTrue(hidden.busy.isEmpty())
        client.syncOk(home, SyncRequest(home.sam, events = listOf(e.copy(showBusy = true, updatedAt = 3_000))))
        assertTrue(client.syncOk(home, SyncRequest(home.kim)).busy.isEmpty())
        client.syncOk(
            home,
            SyncRequest(home.sam, events = listOf(e.copy(updatedAt = 4_000, schedulingMembers = listOf(EventMemberDto(home.kim, 4_000))))),
        )
        assertEquals("Swimming", client.syncOk(home, SyncRequest(home.kim)).busy.single().title)
    }

    @Test
    fun `Everyone for scheduling, with someone taking part`() = api { client, home ->
        val e = shared(home.sam, members = listOf(home.alex), everyone = Everyone.SCHEDULING)
        client.syncOk(home, SyncRequest(home.sam, events = listOf(e)))
        assertEquals(listOf(e.id), client.syncOk(home, SyncRequest(home.alex)).events.map { it.id })
        assertTrue(client.syncOk(home, SyncRequest(home.alex)).busy.isEmpty())
        assertEquals(listOf("Swimming"), client.syncOk(home, SyncRequest(home.kim)).busy.map { it.title })
    }

    @Test
    fun `moving someone to scheduling deletes their reminders on the event`() = api { client, home ->
        val e = shared(home.sam, members = listOf(home.alex, home.kim))
        client.syncOk(home, SyncRequest(home.sam, events = listOf(e)))
        val r = reminder(home.alex, e.id)
        client.syncOk(home, SyncRequest(home.alex, reminders = listOf(r)))
        val kimReminder = reminder(home.kim, e.id)
        client.syncOk(home, SyncRequest(home.kim, reminders = listOf(kimReminder)))

        val moved = e.copy(updatedAt = 2_000, members = listOf(EventMemberDto(home.kim)), schedulingMembers = listOf(EventMemberDto(home.alex, 2_000)))
        client.syncOk(home, SyncRequest(home.sam, events = listOf(moved)))
        val alex = client.syncOk(home, SyncRequest(home.alex))
        assertTrue(alex.events.isEmpty())
        assertTrue(alex.reminders.single().deleted)
        assertEquals("Swimming", alex.busy.single().title)
        assertTrue(!client.syncOk(home, SyncRequest(home.kim)).reminders.single().deleted)
    }

    @Test
    fun `members left out of a shared event see Busy when the owner picks it`() = api { client, home ->
        val busy = shared(home.sam, members = listOf(home.alex), showBusy = true)
        val nothing = shared(home.sam, members = listOf(home.alex))
        client.syncOk(home, SyncRequest(home.sam, events = listOf(busy, nothing)))
        val kim = client.syncOk(home, SyncRequest(home.kim)).busy
        assertEquals(listOf(busy.id), kim.map { it.id })
        assertNull(kim.single().title)
        // Alex takes part in both, so neither is Busy for them.
        assertTrue(client.syncOk(home, SyncRequest(home.alex)).busy.isEmpty())
    }

    @Test
    fun `a phone before 2_4 keeps scheduling members, Everyone and Busy off on shared events`() = api { client, home ->
        val e = shared(home.sam, members = listOf(home.alex), scheduling = listOf(home.kim), everyone = Everyone.SCHEDULING, showBusy = true)
        client.syncOk(home, SyncRequest(home.sam, events = listOf(e)))
        // The old phone sends only the members taking part, and always Busy.
        val old = e.copy(title = "Swim", updatedAt = 2_000, schedulingMembers = null, everyone = null, showBusy = true)
        client.syncOk(home, SyncRequest(home.sam, events = listOf(old)))
        val stored = client.syncOk(home, SyncRequest(home.sam)).events.single()
        assertEquals(listOf(home.kim), stored.schedulingMembers!!.map { it.memberId })
        assertEquals(Everyone.SCHEDULING, stored.everyone)
        assertEquals(false, stored.showBusy)
        assertEquals("Swim", client.syncOk(home, SyncRequest(home.kim)).busy.single().title)
    }

    @Test
    fun `sharing rules`() = api { client, home ->
        val both = shared(home.sam, members = listOf(home.alex), scheduling = listOf(home.alex))
        val owner = shared(home.sam, scheduling = listOf(home.sam))
        val bad = shared(home.sam).copy(everyone = "ALL")
        val out = client.syncOk(home, SyncRequest(home.sam, events = listOf(both, owner, bad)))
        assertEquals(setOf("event:${both.id}", "event:${owner.id}", "event:${bad.id}"), out.rejected.toSet())
        // Only the owner writes it, also its Everyone.
        val e = shared(home.sam)
        client.syncOk(home, SyncRequest(home.sam, events = listOf(e)))
        assertEquals(listOf("event:${e.id}"), client.syncOk(home, SyncRequest(home.alex, events = listOf(e.copy(everyone = Everyone.MEMBER, updatedAt = 9_000)))).rejected)
    }

    /** A weekend away: Fri 18:00 to Sun 20:00, once on a date or every week. */
    private fun weekend(owner: String, oneOff: Boolean = true, updatedAt: Long = 1_000) = event(owner, updatedAt = updatedAt).copy(
        weekdays = if (oneOff) 0 else 0b0010000,
        onDate = if (oneOff) "2026-10-23" else null,
        startMinute = 18 * 60,
        endMinute = null,
        endDays = 2,
        endDayMinute = 20 * 60,
    )

    @Test
    fun `multi-day events come back with their end day, and as first-day-only for phones before 2_2`() = api { client, home ->
        val timed = weekend(home.sam)
        val allDay = weekend(home.sam, oneOff = false).copy(startMinute = null, endDayMinute = null)
        val out = client.syncOk(home, SyncRequest(home.sam, events = listOf(timed, allDay)))
        assertTrue(out.rejected.isEmpty(), out.rejected.toString())
        val t = out.events.single { it.id == timed.id }
        assertEquals(2, t.endDays)
        assertEquals(20 * 60, t.endDayMinute)
        // Old phones: from the start until midnight of the first day.
        assertEquals(1440, t.endMinute)
        val a = out.events.single { it.id == allDay.id }
        assertEquals(2, a.endDays)
        assertNull(a.endMinute)
        assertNull(a.endDayMinute)

        // Same-day events don't change.
        val plain = client.syncOk(home, SyncRequest(home.sam, events = listOf(event(home.sam)))).events.single { it.endDays == 0 }
        assertEquals(18 * 60, plain.endMinute)
        assertNull(plain.endDayMinute)

        // Busy carries the span too.
        val busy = client.syncOk(home, SyncRequest(home.alex)).busy.single { it.id == timed.id }
        assertEquals(2, busy.endDays)
        assertEquals(20 * 60, busy.endDayMinute)
        assertEquals(1440, busy.endMinute)
    }

    @Test
    fun `a phone before 2_2 editing a multi-day event keeps its end`() = api { client, home ->
        val e = weekend(home.sam)
        client.syncOk(home, SyncRequest(home.sam, events = listOf(e)))
        // What a 2.1 phone sends back: the first-day end it was given, and no endDays.
        val old = e.copy(title = "Away", endMinute = 1440, endDays = null, endDayMinute = null, updatedAt = 2_000)
        val back = client.syncOk(home, SyncRequest(home.sam, events = listOf(old))).events.single()
        assertEquals("Away", back.title)
        assertEquals(2, back.endDays)
        assertEquals(20 * 60, back.endDayMinute)
        // A 2.2 phone can make it a same-day event again.
        val same = e.copy(endDays = 0, endMinute = 19 * 60, endDayMinute = null, updatedAt = 3_000)
        val now = client.syncOk(home, SyncRequest(home.sam, events = listOf(same))).events.single()
        assertEquals(0, now.endDays)
        assertEquals(19 * 60, now.endMinute)
    }

    @Test
    fun `multi-day rules`() = api { client, home ->
        val ok31 = weekend(home.sam).copy(endDays = 31)
        val tooLong = weekend(home.sam).copy(endDays = 32)
        val weeklyOk = weekend(home.sam, oneOff = false).copy(endDays = 6)
        val weeklyTooLong = weekend(home.sam, oneOff = false).copy(endDays = 7)
        val weeklyTwoDays = weekend(home.sam, oneOff = false).copy(weekdays = 0b0010001)
        val noEndTime = weekend(home.sam).copy(endDayMinute = null)
        val endWithoutStart = weekend(home.sam).copy(startMinute = null)
        // Ending at or before the start time is fine on a later day.
        val early = weekend(home.sam).copy(endDays = 1, endDayMinute = 9 * 60)
        val out = client.syncOk(home, SyncRequest(home.sam, events = listOf(ok31, tooLong, weeklyOk, weeklyTooLong, weeklyTwoDays, noEndTime, endWithoutStart, early)))
        assertEquals(listOf(tooLong, weeklyTooLong, weeklyTwoDays, noEndTime, endWithoutStart).map { "event:${it.id}" }, out.rejected)

        // A 2.1 phone can't turn a multi-day weekly event into one on several weekdays.
        val stored = weekend(home.sam, oneOff = false)
        client.syncOk(home, SyncRequest(home.sam, events = listOf(stored)))
        val twoDays = stored.copy(weekdays = 0b0010001, endMinute = 1440, endDays = null, endDayMinute = null, updatedAt = 2_000)
        assertEquals(listOf("event:${stored.id}"), client.syncOk(home, SyncRequest(home.sam, events = listOf(twoDays))).rejected)
    }

    @Test
    fun `each day on persistent reminders, kept when a phone before 2_2 leaves it out`() = api { client, home ->
        val e = weekend(home.sam)
        val r = reminder(home.sam, e.id).copy(mode = "PERSISTENT", times = emptyList(), eachDay = true)
        val out = client.syncOk(home, SyncRequest(home.sam, events = listOf(e), reminders = listOf(r)))
        assertEquals(true, out.reminders.single().eachDay)
        val old = r.copy(active = false, eachDay = null, updatedAt = 2_000)
        val back = client.syncOk(home, SyncRequest(home.sam, reminders = listOf(old))).reminders.single()
        assertEquals(false, back.active)
        assertEquals(true, back.eachDay)

        // A 2.1 phone setting a lead makes it a whole-event reminder again.
        val lead = r.copy(leadMinutes = 30, eachDay = null, updatedAt = 3_000)
        assertEquals(false, client.syncOk(home, SyncRequest(home.sam, reminders = listOf(lead))).reminders.single().eachDay)

        val timed = reminder(home.sam, e.id).copy(eachDay = true)
        val withLead = r.copy(id = UUID.randomUUID().toString(), leadMinutes = 30)
        val rejected = client.syncOk(home, SyncRequest(home.sam, reminders = listOf(timed, withLead))).rejected
        assertEquals(listOf(timed.id, withLead.id), rejected)
    }
}
