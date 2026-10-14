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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 9.5: one-tap invite links into a household. */
class MemberInvitesTest {
    private lateinit var api: ApiContext
    private val db get() = ApiTest.postgres.postgresDatabase
    private var now = Instant.parse("2026-10-11T12:00:00Z")
    private val PIN = "482193"

    @BeforeTest
    fun setUp() {
        db.connection.use { it.createStatement().execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;") }
        migrate(db)
        api = ApiContext(
            HouseholdStore(db), ChoreStore(db), SyncStore(db), Secrets(iterations = 1_000), AttemptLimiter(maxFailures = 5, clock = { now }),
            setupToken = "setup", clock = { now },
            // The 20-invite test makes more admin requests than the per-IP limit allows in a minute.
            rateLimits = RateLimits(admin = 100),
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

    private fun HttpRequestBuilder.admin(token: String, pin: String = PIN) {
        header(HttpHeaders.Authorization, "Bearer $token")
        header(ADMIN_PIN_HEADER, pin)
    }

    private suspend fun HttpClient.createHousehold(name: String = "Home"): SessionResponse =
        post("/household") { json(CreateHouseholdRequest(name, "Europe/London", "Sam", PIN, setupToken = "setup")) }
            .also { assertEquals(HttpStatusCode.Created, it.status) }.body()

    private suspend fun HttpClient.join(code: String, name: String = "Alex", adminPin: String? = null): HttpResponse =
        post("/join") { json(JoinRequest(code, name, adminPin = adminPin)) }

    private suspend fun HttpClient.newInvite(token: String): CreatedInviteResponse =
        post("/member-invites") { admin(token) }.also { assertEquals(HttpStatusCode.Created, it.status) }.body()

    private suspend fun HttpClient.openInvites(token: String): List<InviteDto> =
        get("/member-invites") { admin(token) }.body<MemberInvitesResponse>().invites

    @Test
    fun `invites fit the 24 characters 2_0 apps accept and read back as themselves`() {
        repeat(50) {
            val code = Codes.newMemberInvite()
            assertTrue(code.length <= 24, code)
            assertTrue(Regex("JOIN(-[2-9A-HJ-NP-Z]{4}){4}").matches(code), code)
            assertEquals(code.replace("-", ""), Codes.normalizeMemberInvite(code.lowercase().replace("-", " ")))
            // Never mistaken for a join code or a household invite.
            assertNull(Codes.parseJoinCode(code))
        }
        assertNull(Codes.normalizeMemberInvite("JOIN-2222"))
        assertNull(Codes.normalizeMemberInvite("HZ7K-2P9X-QM4T"))
    }

    @Test
    fun `an invite lets one phone ask to join, even without a join code`() = api { client ->
        val home = client.createHousehold()
        // Households from before 2.0 have no join code: the invite still works.
        db.connection.use { it.createStatement().execute("UPDATE household SET code_hash = NULL") }
        val created = client.newInvite(home.token)
        assertEquals(1, client.openInvites(home.token).size)

        val joined = client.join(created.code).also { assertEquals(HttpStatusCode.OK, it.status) }.body<SessionResponse>()
        assertEquals("pending", joined.deviceStatus)
        assertEquals(home.household.id, joined.household.id)
        assertEquals(listOf("Alex"), client.get("/join-requests") { admin(home.token) }.body<List<JoinRequestDto>>().map { it.name })

        // Used up: gone from the open list, and a second phone can't use it.
        assertTrue(client.openInvites(home.token).isEmpty())
        assertEquals(HttpStatusCode.Forbidden, client.join(created.code, "Kim").status)
    }

    @Test
    fun `cancelled and expired invites don't work`() = api { client ->
        val home = client.createHousehold()
        val cancelled = client.newInvite(home.token)
        assertEquals(HttpStatusCode.NoContent, client.delete("/member-invites/${cancelled.invite.id}") { admin(home.token) }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/member-invites/${cancelled.invite.id}") { admin(home.token) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.join(cancelled.code).status)

        val expiring = client.newInvite(home.token)
        now = now.plus(Duration.ofDays(7)).plusSeconds(1)
        assertTrue(client.openInvites(home.token).isEmpty())
        assertEquals(HttpStatusCode.Forbidden, client.join(expiring.code).status)
    }

    @Test
    fun `an invite only works for its own household`() = api { client ->
        val a = client.createHousehold("A")
        val b = client.createHousehold("B")
        val invite = client.newInvite(a.token)
        val joined = client.join(invite.code).body<SessionResponse>()
        assertEquals(a.household.id, joined.household.id)
        assertTrue(client.openInvites(b.token).isEmpty())
        // B's admin can't cancel A's invites.
        val other = client.newInvite(a.token)
        assertEquals(HttpStatusCode.NotFound, client.delete("/member-invites/${other.invite.id}") { admin(b.token) }.status)
    }

    @Test
    fun `the admin joins a new phone with an invite and their PIN, and a wrong PIN doesn't use it up`() = api { client ->
        val home = client.createHousehold()
        val invite = client.newInvite(home.token)
        assertEquals(HttpStatusCode.Forbidden, client.join(invite.code, "", adminPin = "0000").status)
        assertEquals(1, client.openInvites(home.token).size)

        val admin = client.join(invite.code, "", adminPin = PIN).also { assertEquals(HttpStatusCode.OK, it.status) }.body<SessionResponse>()
        assertEquals(home.memberId, admin.memberId)
        assertTrue(client.openInvites(home.token).isEmpty())
    }

    @Test
    fun `at most 20 open invites, made only with the admin PIN`() = api { client ->
        val home = client.createHousehold()
        repeat(20) { client.newInvite(home.token) }
        assertEquals(HttpStatusCode.Conflict, client.post("/member-invites") { admin(home.token) }.status)
        // Cancelling one frees a place.
        client.delete("/member-invites/${client.openInvites(home.token).first().id}") { admin(home.token) }
        client.newInvite(home.token)

        assertEquals(HttpStatusCode.Forbidden, client.post("/member-invites") { admin(home.token, pin = "0000") }.status)
    }

    @Test
    fun `made-up invites count towards the sender's lock`() = api { client ->
        val home = client.createHousehold()
        repeat(5) { assertEquals(HttpStatusCode.Forbidden, client.join(Codes.newMemberInvite()).status) }
        assertEquals(HttpStatusCode.TooManyRequests, client.join(client.newInvite(home.token).code).status)
    }
}
