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
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Phase 9: invites, generated join codes, deleting households, cleanup and the owner tool. */
class HouseholdsTest {
    private lateinit var api: ApiContext
    private val db get() = ApiTest.postgres.postgresDatabase
    private var now = Instant.parse("2026-10-11T12:00:00Z")
    private val sent = mutableListOf<Set<String>>()
    private val PIN = "4821"

    @BeforeTest
    fun setUp() {
        db.connection.use { it.createStatement().execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;") }
        migrate(db)
        sent.clear()
        api = ApiContext(
            HouseholdStore(db), ChoreStore(db), SyncStore(db), Secrets(iterations = 1_000), AttemptLimiter(maxFailures = 5, clock = { now }),
            setupToken = "setup", clock = { now },
            push = Push(PushStore(db)) { tokens -> if (tokens.isNotEmpty()) sent += tokens.toSet() },
        )
    }

    private fun api(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application { module(api) }
        block(createClient { install(ContentNegotiation) { json() } })
    }

    private fun HttpRequestBuilder.json(body: Any) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun HttpRequestBuilder.admin(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
        header(ADMIN_PIN_HEADER, PIN)
    }

    private fun create(name: String = "Home", setupToken: String? = "setup", invite: String? = null) =
        CreateHouseholdRequest(name, "Europe/London", "Sam", PIN, setupToken = setupToken, inviteCode = invite)

    private suspend fun HttpClient.createHousehold(name: String = "Home", invite: String? = null): SessionResponse =
        post("/household") { json(create(name, setupToken = if (invite == null) "setup" else null, invite = invite)) }
            .also { assertEquals(HttpStatusCode.Created, it.status) }.body()

    private suspend fun HttpClient.join(code: String, name: String = "Alex", adminPin: String? = null): HttpResponse =
        post("/join") { json(JoinRequest(code, name, adminPin = adminPin)) }

    private suspend fun HttpClient.invite(token: String): HttpResponse = post("/invites") { admin(token) }

    @Test
    fun `apps from before 2_0 are told to update, never logged out`() = api { client ->
        client.createHousehold()
        val oldJoin = client.post("/join") { json(JoinRequest(householdCode = "kitchen-table", name = "Alex")) }
        assertEquals(HttpStatusCode.Gone, oldJoin.status)
        val oldCreate = client.post("/household") { json(create().copy(householdCode = "kitchen-table")) }
        assertEquals(HttpStatusCode.Gone, oldCreate.status)
    }

    @Test
    fun `a new household gets a join code that works, in any case and spacing`() = api { client ->
        val session = client.createHousehold()
        val code = assertNotNull(session.joinCode)
        assertTrue(Regex("[2-9A-HJ-NP-Z]{4}-[2-9A-HJ-NP-Z]{4}-[2-9A-HJ-NP-Z]{4}").matches(code), code)
        assertEquals(HttpStatusCode.OK, client.join(code.lowercase().replace("-", " ")).status)
        assertTrue(client.get("/me") { header(HttpHeaders.Authorization, "Bearer ${session.token}") }.body<HouseholdResponse>().hasJoinCode)
    }

    @Test
    fun `a new join code replaces the old one, and households from before 2_0 have none`() = api { client ->
        val session = client.createHousehold()
        val fresh = client.post("/household/join-code") { admin(session.token) }.body<JoinCodeResponse>().joinCode
        assertEquals(HttpStatusCode.Forbidden, client.join(session.joinCode!!).status)
        assertEquals(HttpStatusCode.OK, client.join(fresh, "Kim").status)

        // V11 clears the code of existing households: nobody joins until the admin makes a new one.
        db.connection.use { it.createStatement().execute("UPDATE household SET code_hash = NULL") }
        assertEquals(HttpStatusCode.Forbidden, client.join(fresh, "Jo").status)
        assertFalse(client.get("/me") { header(HttpHeaders.Authorization, "Bearer ${session.token}") }.body<HouseholdResponse>().hasJoinCode)
    }

    @Test
    fun `wrong join codes lock only that household`() = api { client ->
        val a = client.createHousehold("A")
        val b = client.createHousehold("B")
        // The right join id with a wrong secret.
        val wrong = a.joinCode!!.take(5) + "2222-2222"
        repeat(5) { assertEquals(HttpStatusCode.Forbidden, client.join(wrong).status) }
        assertEquals(HttpStatusCode.TooManyRequests, client.join(a.joinCode!!).status)
        assertEquals(HttpStatusCode.OK, client.join(b.joinCode!!).status)
        // Wrong admin PINs also count per household: A's admin routes are locked, B's aren't.
        assertEquals(HttpStatusCode.TooManyRequests, client.post("/admin/verify") { admin(a.token) }.status)
        assertEquals(HttpStatusCode.NoContent, client.post("/admin/verify") { admin(b.token) }.status)
        now = now.plus(Duration.ofMinutes(16))
        assertEquals(HttpStatusCode.OK, client.join(a.joinCode!!).status)
    }

    @Test
    fun `an invite creates a separate household once`() = api { client ->
        val home = client.createHousehold()
        val created = client.invite(home.token).also { assertEquals(HttpStatusCode.Created, it.status) }.body<CreatedInviteResponse>()
        assertTrue(created.code.startsWith("NEW-"))

        val friend = client.createHousehold("Friends", invite = created.code.lowercase())
        assertTrue(friend.household.id != home.household.id)
        assertEquals("Friends", friend.household.name)
        // Used up: a second household can't be made with it.
        val again = client.post("/household") { json(create("Again", setupToken = null, invite = created.code)) }
        assertEquals(HttpStatusCode.Forbidden, again.status)

        val invites = client.get("/invites") { admin(home.token) }.body<InvitesResponse>()
        assertNotNull(invites.invites.single().usedAt)
        assertEquals(2, invites.remaining)
        // The friend's household has its own invites and admin PIN checks.
        assertEquals(3, client.get("/invites") { admin(friend.token) }.body<InvitesResponse>().remaining)
        assertEquals(HttpStatusCode.NotFound, client.delete("/invites/${created.invite.id}") { admin(friend.token) }.status)
    }

    @Test
    fun `creating needs a valid invite or the setup code`() = api { client ->
        assertEquals(HttpStatusCode.BadRequest, client.post("/household") { json(create(setupToken = null)) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/household") { json(create(setupToken = "wrong")) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/household") { json(create(setupToken = null, invite = "NEW-2222-2222-2222")) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/household") { json(create(setupToken = null, invite = "not an invite")) }.status)
    }

    @Test
    fun `three invites per household, and cancelled or expired ones give their slot back`() = api { client ->
        val home = client.createHousehold()
        val first = client.invite(home.token).body<CreatedInviteResponse>()
        val second = client.invite(home.token).body<CreatedInviteResponse>()
        client.invite(home.token)
        assertEquals(HttpStatusCode.Conflict, client.invite(home.token).status)

        assertEquals(HttpStatusCode.NoContent, client.delete("/invites/${first.invite.id}") { admin(home.token) }.status)
        assertEquals(HttpStatusCode.Created, client.invite(home.token).status)

        // After 7 days unused invites expire: they can't be used, and the slots come back.
        now = now.plus(Duration.ofDays(8))
        assertEquals(HttpStatusCode.Forbidden, client.post("/household") { json(create(setupToken = null, invite = second.code)) }.status)
        assertEquals(3, client.get("/invites") { admin(home.token) }.body<InvitesResponse>().remaining)
    }

    @Test
    fun `the admin deletes the household and everything in it`() = api { client ->
        val home = client.createHousehold()
        val other = client.createHousehold("Other")
        val alexToken = client.join(home.joinCode!!).body<SessionResponse>().token
        val requestId = client.get("/join-requests") { admin(home.token) }.body<List<JoinRequestDto>>().single().id
        val alex = client.post("/join-requests/$requestId/approve") { admin(home.token); json(ApproveRequest()) }.body<MemberDto>().id
        client.put("/me/push-token") { header(HttpHeaders.Authorization, "Bearer $alexToken"); json(PushTokenRequest("fcm-alex")) }
        val event = UUID.randomUUID().toString()
        client.post("/v2/sync") {
            header(HttpHeaders.Authorization, "Bearer $alexToken")
            json(
                SyncRequest(
                    alex,
                    events = listOf(EventSyncDto(event, alex, "Swim", weekdays = 1, members = listOf(EventMemberDto(home.household.adminMemberId!!)), createdAt = 1, updatedAt = 1)),
                    reminders = listOf(ReminderSyncDto(UUID.randomUUID().toString(), alex, eventId = event, mode = "TIMED", times = listOf(TimeDto(60)), createdAt = 1, updatedAt = 1)),
                    settings = SettingsSyncDto(10, "BOTH", "SILENT", 1),
                ),
            )
        }
        client.put("/chores/${UUID.randomUUID()}") {
            header(HttpHeaders.Authorization, "Bearer ${home.token}")
            json(ChoreRequest("Bins", weekdays = 1, completion = ChoreCompletion.ANY, assigneeIds = listOf(alex)))
        }
        client.invite(home.token)
        sent.clear()

        assertEquals(HttpStatusCode.Forbidden, client.delete("/household") { header(HttpHeaders.Authorization, "Bearer ${home.token}"); header(ADMIN_PIN_HEADER, "0000") }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/household") { admin(home.token) }.status)
        // Alex's phone is told, and finds itself logged out.
        assertEquals(listOf(setOf("fcm-alex")), sent)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/me") { header(HttpHeaders.Authorization, "Bearer $alexToken") }.status)

        val left = db.connection.use { conn ->
            listOf("household", "member", "device", "chore", "chore_assignment", "event", "event_member", "reminder", "reminder_time", "member_settings", "household_invite")
                .associateWith { table -> conn.createStatement().executeQuery("SELECT count(*) FROM $table").use { it.next(); it.getInt(1) } }
        }
        // Only the other household (with its admin, their settings and phone) is left.
        assertEquals(mapOf("household" to 1, "member" to 1, "device" to 1, "member_settings" to 1), left.filterValues { it > 0 })
        assertEquals(HttpStatusCode.OK, client.get("/me") { header(HttpHeaders.Authorization, "Bearer ${other.token}") }.status)
    }

    @Test
    fun `unused households are marked, cleared by activity, and deleted after the grace period`() = api { client ->
        val ours = client.createHousehold("Ours")
        db.connection.use { it.createStatement().execute("UPDATE household SET protected = TRUE") }
        val quiet = UUID.fromString(client.createHousehold("Quiet").household.id)
        val busy = UUID.fromString(client.createHousehold("Busy").household.id)
        val cleanup = Cleanup(db)
        // Last activity is the database's clock (phones seen, households created), so time moves in the runs.
        val t0 = Instant.now()

        assertEquals(Cleanup.Outcome(emptyList(), emptyList(), emptyList()), cleanup.run(t0.plus(Duration.ofDays(100))))
        // A year on, both are marked; ours never is.
        val year = t0.plus(Duration.ofDays(366))
        assertEquals(setOf(quiet, busy), cleanup.run(year).marked.toSet())
        // Busy's phone comes back during the grace period.
        db.connection.use { conn ->
            conn.prepareStatement("UPDATE device SET last_seen_at = ? WHERE household_id = ?").run {
                setObject(1, year.plus(Duration.ofDays(1)).toOffset()); setObject(2, busy); executeUpdate()
            }
        }
        val outcome = cleanup.run(year.plus(Duration.ofDays(31)))
        assertEquals(listOf(busy), outcome.cleared)
        assertEquals(listOf(quiet), outcome.deleted)
        assertEquals(setOf(UUID.fromString(ours.household.id), busy), OwnerTool(db).rows().map { it.id }.toSet())
    }

    @Test
    fun `the owner tool lists no names and removes a household with the ones it invited`() = api { client ->
        val ours = client.createHousehold("Ours")
        db.connection.use { it.createStatement().execute("UPDATE household SET protected = TRUE") }
        val friend = client.createHousehold("Friend", invite = client.invite(ours.token).body<CreatedInviteResponse>().code)
        val friendOfFriend = client.createHousehold("FriendOfFriend", invite = client.invite(friend.token).body<CreatedInviteResponse>().code)
        val tool = OwnerTool(db)

        val listing = tool.list()
        assertFalse("Friend" in listing || "Sam" in listing || "Ours" in listing, listing)
        assertTrue(friend.household.id in listing && "protected" in listing)

        assertEquals(OwnerTool.Removal.Protected, tool.remove(UUID.fromString(ours.household.id)))
        val removed = tool.remove(UUID.fromString(friend.household.id))
        assertEquals(OwnerTool.Removal.Removed(listOf(friend.household.id, friendOfFriend.household.id).map(UUID::fromString)), removed)
        assertEquals(listOf(ours.household.id), tool.rows().map { it.id.toString() })
    }
}
