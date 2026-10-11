package app.everyday.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
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
import kotlin.test.assertTrue

/** Two households on one server: neither can see or change anything of the other's (Phase 9). */
class IsolationTest {
    private lateinit var api: ApiContext
    private val db get() = ApiTest.postgres.postgresDatabase
    private val sent = mutableListOf<Set<String>>()

    @BeforeTest
    fun setUp() {
        db.connection.use { it.createStatement().execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;") }
        migrate(db)
        sent.clear()
        val push = Push(PushStore(db)) { tokens -> if (tokens.isNotEmpty()) sent += tokens.toSet() }
        api = ApiContext(
            HouseholdStore(db), ChoreStore(db), SyncStore(db), Secrets(iterations = 1_000), AttemptLimiter(),
            setupToken = "setup", push = push,
        )
    }

    /** A household's admin phone, a second member without a phone, and one of each kind of row. */
    private class Home(
        val id: String,
        val token: String,
        val admin: String,
        val other: String,
        val chore: String,
        val event: String,
        val reminder: String,
    )

    private fun HttpRequestBuilder.auth(token: String) = header(HttpHeaders.Authorization, "Bearer $token")

    private fun HttpRequestBuilder.json(body: Any) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpClient.home(name: String, adminName: String, otherName: String, pushToken: String): Home {
        val session = post("/household") {
            json(CreateHouseholdRequest("setup", name, "code-$name", "Europe/London", adminName, "4821"))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }.body<SessionResponse>()
        val token = session.token
        val admin = session.household.adminMemberId!!
        put("/me/push-token") { auth(token); json(PushTokenRequest(pushToken)) }
        val other = post("/members") { auth(token); header(ADMIN_PIN_HEADER, "4821"); json(MemberRequest(otherName)) }
            .body<MemberDto>().id
        val chore = UUID.randomUUID().toString()
        put("/chores/$chore") { auth(token); json(ChoreRequest("Bins $name", weekdays = 1, completion = ChoreCompletion.EACH, assigneeIds = listOf(admin))) }
        val event = UUID.randomUUID().toString()
        val reminder = UUID.randomUUID().toString()
        post("/v2/sync") {
            auth(token)
            json(
                SyncRequest(
                    admin,
                    // A personal event that shows as Busy to this household's other members.
                    events = listOf(EventSyncDto(event, admin, "Dentist $name", weekdays = 1, startMinute = 600, createdAt = 1, updatedAt = 1)),
                    reminders = listOf(ReminderSyncDto(reminder, admin, eventId = event, mode = "TIMED", times = listOf(TimeDto(480)), createdAt = 1, updatedAt = 1)),
                ),
            )
        }.also { assertEquals(HttpStatusCode.OK, it.status) }
        return Home(session.household.id, token, admin, other, chore, event, reminder)
    }

    private fun api(block: suspend ApplicationTestBuilder.(HttpClient, Home, Home) -> Unit) = testApplication {
        application { module(api) }
        val client = createClient { install(ContentNegotiation) { json() } }
        // The same names in both households: names and colours are unique per household only.
        val a = client.home("A", "Sam", "Alex", "fcm-a")
        val b = client.home("B", "Sam", "Alex", "fcm-b")
        sent.clear()
        block(client, a, b)
    }

    @Test
    fun `two households can be created, with the same member names`() = api { _, a, b ->
        assertTrue(a.id != b.id)
        assertEquals(listOf("Sam", "Alex"), api.store.members(UUID.fromString(b.id)).map { it.displayName })
    }

    @Test
    fun `members and household details stay in their household`() = api { client, a, b ->
        val me = client.get("/me") { auth(b.token) }.body<HouseholdResponse>()
        assertEquals(b.id, me.household.id)
        assertEquals(setOf(b.admin, b.other), me.members.map { it.id }.toSet())

        val pin = "4821"
        assertEquals(HttpStatusCode.NotFound, client.put("/members/${a.other}") { auth(b.token); header(ADMIN_PIN_HEADER, pin); json(MemberRequest("Mine")) }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/members/${a.other}") { auth(b.token); header(ADMIN_PIN_HEADER, pin) }.status)
        assertEquals("Alex", api.store.members(UUID.fromString(a.id)).single { it.id == a.other }.displayName)
    }

    @Test
    fun `join requests stay in their household`() = api { client, a, b ->
        api.store.addPendingDevice(UUID.fromString(a.id), Secrets.tokenHash("pending-a"), "phone", "Kim")
        val pendingId = client.get("/me") { auth(a.token) }.body<HouseholdResponse>().pendingRequestIds.single()

        assertEquals(emptyList(), client.get("/me") { auth(b.token) }.body<HouseholdResponse>().pendingRequestIds)
        assertEquals(emptyList(), client.get("/join-requests") { auth(b.token); header(ADMIN_PIN_HEADER, "4821") }.body<List<JoinRequestDto>>())
        val approve = client.post("/join-requests/$pendingId/approve") { auth(b.token); header(ADMIN_PIN_HEADER, "4821"); json(ApproveRequest()) }
        assertEquals(HttpStatusCode.NotFound, approve.status)
        // Linking one of A's phones to one of B's members is refused as well.
        val link = client.post("/join-requests/$pendingId/approve") { auth(a.token); header(ADMIN_PIN_HEADER, "4821"); json(ApproveRequest(b.other)) }
        assertEquals(HttpStatusCode.NotFound, link.status)
        assertEquals(HttpStatusCode.NotFound, client.post("/join-requests/$pendingId/reject") { auth(b.token); header(ADMIN_PIN_HEADER, "4821") }.status)
    }

    @Test
    fun `chores stay in their household`() = api { client, a, b ->
        val chores = client.get("/chores") { auth(b.token) }.body<ChoresResponse>()
        assertEquals(listOf(b.chore), chores.chores.map { it.id })

        // A's chore id from B's phone: never found, never overwritten.
        val overwrite = client.put("/chores/${a.chore}") { auth(b.token); json(ChoreRequest("Mine", weekdays = 1, completion = ChoreCompletion.ANY, assigneeIds = listOf(b.admin))) }
        assertEquals(HttpStatusCode.NotFound, overwrite.status)
        assertEquals(HttpStatusCode.NotFound, client.post("/chores/${a.chore}/done") { auth(b.token); json(ChoreDoneRequest(b.admin)) }.status)
        assertEquals(HttpStatusCode.NotFound, client.post("/chores/${a.chore}/undo") { auth(b.token); json(ChoreDoneRequest(b.admin)) }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/chores/${a.chore}") { auth(b.token) }.status)
        // Assigning one of A's members to B's chore.
        val assign = client.put("/chores/${b.chore}") { auth(b.token); json(ChoreRequest("Bins", weekdays = 1, completion = ChoreCompletion.ANY, assigneeIds = listOf(a.other))) }
        assertEquals(HttpStatusCode.BadRequest, assign.status)

        assertEquals("Bins A", client.get("/chores") { auth(a.token) }.body<ChoresResponse>().chores.single().title)
    }

    @Test
    fun `events, Busy and reminders stay in their household`() = api { client, a, b ->
        val snapshot = client.post("/v2/sync") { auth(b.token); json(SyncRequest(b.admin)) }.body<SyncResponse>()
        assertEquals(listOf(b.event), snapshot.events.map { it.id })
        assertEquals(listOf(b.reminder), snapshot.reminders.map { it.id })
        // B's admin sees no Busy from A (their own personal event isn't Busy to themselves).
        assertEquals(emptyList(), snapshot.busy)

        val attempts = SyncRequest(
            b.admin,
            // Overwriting A's event, sharing an event with A's member, a reminder on A's event,
            // overwriting A's reminder, and state on A's reminder.
            events = listOf(
                EventSyncDto(a.event, b.admin, "Mine", weekdays = 1, createdAt = 1, updatedAt = 99),
                EventSyncDto(UUID.randomUUID().toString(), b.admin, "Share", weekdays = 1, members = listOf(EventMemberDto(a.other)), createdAt = 1, updatedAt = 1),
            ),
            reminders = listOf(
                ReminderSyncDto(UUID.randomUUID().toString(), b.admin, eventId = a.event, mode = "TIMED", times = listOf(TimeDto(480)), createdAt = 1, updatedAt = 1),
                ReminderSyncDto(a.reminder, b.admin, eventId = b.event, mode = "TIMED", times = listOf(TimeDto(480)), createdAt = 1, updatedAt = 99),
                ReminderSyncDto(UUID.randomUUID().toString(), b.admin, choreId = a.chore, message = "Bins", mode = "TIMED", weekdays = 1, times = listOf(TimeDto(480)), createdAt = 1, updatedAt = 1),
            ),
            states = listOf(StateSyncDto(a.reminder, doneOn = "2026-10-11", updatedAt = 99)),
            leave = listOf(LeaveSyncDto(a.event, 99)),
        )
        val out = client.post("/v2/sync") { auth(b.token); json(attempts) }.body<SyncResponse>()
        // Two events, three reminders, one state and one leave.
        assertEquals(7, out.rejected.size, out.rejected.toString())
        assertEquals(emptyList(), sent)

        // A's rows are unchanged.
        val aSnapshot = client.post("/v2/sync") { auth(a.token); json(SyncRequest(a.admin)) }.body<SyncResponse>()
        assertEquals("Dentist A", aSnapshot.events.single().title)
        assertEquals(1, aSnapshot.reminders.size)
    }

    @Test
    fun `Busy events show only to the owner's own household`() = api { client, a, b ->
        // Give B's second member a phone, then read their Busy list.
        api.store.addPendingDevice(UUID.fromString(b.id), Secrets.tokenHash("b-other"), "phone", "Alex 2")
        val pendingId = client.get("/me") { auth(b.token) }.body<HouseholdResponse>().pendingRequestIds.single()
        client.post("/join-requests/$pendingId/approve") { auth(b.token); header(ADMIN_PIN_HEADER, "4821"); json(ApproveRequest(b.other)) }
        val busy = client.post("/v2/sync") { auth("b-other"); json(SyncRequest(b.other)) }.body<SyncResponse>().busy
        assertEquals(listOf(b.event), busy.map { it.id })
    }

    @Test
    fun `pushes stay in their household`() = api { client, a, b ->
        val chore = UUID.randomUUID().toString()
        client.put("/chores/$chore") { auth(b.token); json(ChoreRequest("Dishes", weekdays = 1, completion = ChoreCompletion.ANY, assigneeIds = listOf(b.admin))) }
        // B's only phone made the change, and A's phone is never told.
        assertEquals(emptyList(), sent)

        // One phone in both households registers the same FCM token in each; both keep it.
        client.put("/me/push-token") { auth(a.token); json(PushTokenRequest("fcm-shared")) }
        client.put("/me/push-token") { auth(b.token); json(PushTokenRequest("fcm-shared")) }
        assertEquals(listOf("fcm-shared"), PushStore(db).householdTokens(UUID.fromString(a.id), null))
        assertEquals(listOf("fcm-shared"), PushStore(db).householdTokens(UUID.fromString(b.id), null))
    }
}
