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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Calls (Phase 12): who can ring whom, the 30 s gap, answers, and which phones are pushed. */
class CallsTest {
    private lateinit var api: ApiContext
    private val db get() = ApiTest.postgres.postgresDatabase
    private var now = Instant.parse("2026-10-23T12:00:00Z")
    private var joinCode = ""

    /** Each call push: its type and the push tokens it went to. */
    private val sent = mutableListOf<Pair<String, Set<String>>>()

    @BeforeTest
    fun setUp() {
        db.connection.use { it.createStatement().execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;") }
        migrate(db)
        sent.clear()
        val sender = object : PushSender {
            override fun send(tokens: List<String>) = Unit
            override fun sendCall(tokens: List<String>, type: String, callId: UUID) {
                sent += type to tokens.toSet()
            }
        }
        api = ApiContext(
            HouseholdStore(db), ChoreStore(db), SyncStore(db), Secrets(iterations = 1_000), AttemptLimiter(),
            setupToken = "setup", clock = { now }, push = Push(PushStore(db), sender),
        )
    }

    /** Sam (admin), Alex and Kim, each with one phone registered as push token "fcm-<name>". */
    private class Home(val samToken: String, val alex: String, val alexToken: String, val kim: String, val kimToken: String, val sam: String)

    private fun api(block: suspend ApplicationTestBuilder.(HttpClient, Home) -> Unit) = testApplication {
        application { module(api) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val session = client.post("/household") {
            contentType(ContentType.Application.Json)
            setBody(CreateHouseholdRequest("Home", "Europe/London", "Sam", PIN, setupToken = "setup"))
        }.body<SessionResponse>()
        joinCode = session.joinCode!!
        client.put("/me/push-token") { auth(session.token); setBody(PushTokenRequest("fcm-sam")) }
        val (alexToken, alex) = client.joinApproved(session.token, "Alex")
        val (kimToken, kim) = client.joinApproved(session.token, "Kim")
        sent.clear()
        block(client, Home(session.token, alex, alexToken, kim, kimToken, session.household.adminMemberId!!))
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth(token: String) {
        contentType(ContentType.Application.Json); header(HttpHeaders.Authorization, "Bearer $token")
    }

    private suspend fun HttpClient.joinApproved(adminToken: String, name: String): Pair<String, String> {
        val token = post("/join") { contentType(ContentType.Application.Json); setBody(JoinRequest(joinCode, name)) }.body<SessionResponse>().token
        put("/me/push-token") { auth(token); setBody(PushTokenRequest("fcm-${name.lowercase()}")) }
        val request = get("/me") { auth(adminToken) }.body<HouseholdResponse>().pendingRequestIds.single()
        val member = post("/join-requests/$request/approve") {
            auth(adminToken); header(ADMIN_PIN_HEADER, PIN); setBody(ApproveRequest())
        }.body<MemberDto>()
        return token to member.id
    }

    private suspend fun HttpClient.ring(token: String, vararg members: String, message: String = "Come downstairs"): HttpResponse =
        post("/calls") { auth(token); setBody(StartCallRequest(members.toList(), message)) }

    private suspend fun HttpClient.answer(token: String, callId: String, answer: String): HttpResponse =
        post("/calls/$callId/answer") { auth(token); setBody(AnswerCallRequest(answer)) }

    private suspend fun HttpClient.allowCalls(adminToken: String, member: String, name: String, canCall: Boolean) =
        put("/members/$member") { auth(adminToken); header(ADMIN_PIN_HEADER, PIN); setBody(MemberRequest(name, canCall = canCall)) }

    @Test
    fun `the admin can always ring, and the members rung are pushed`() = api { client, home ->
        val response = client.ring(home.samToken, home.alex, home.kim)
        assertEquals(HttpStatusCode.Created, response.status)
        val call = response.body<CallDto>()
        assertEquals("Sam", call.callerName)
        assertEquals(listOf("Alex", "Kim"), call.targets.map { it.displayName })
        assertEquals(listOf(Push.CALL to setOf("fcm-alex", "fcm-kim")), sent)
    }

    @Test
    fun `a member rings only once the admin lets them`() = api { client, home ->
        assertEquals(HttpStatusCode.Forbidden, client.ring(home.alexToken, home.kim).status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/calls/targets") { auth(home.alexToken) }.status)

        val alex = client.allowCalls(home.samToken, home.alex, "Alex", canCall = true).body<MemberDto>()
        assertTrue(alex.canCall)
        // Renaming without the flag (apps before 2.8) keeps it.
        assertTrue(client.allowCalls(home.samToken, home.alex, "Alex", canCall = true).body<MemberDto>().canCall)
        assertTrue(client.put("/members/${home.alex}") { auth(home.samToken); header(ADMIN_PIN_HEADER, PIN); setBody(MemberRequest("Alexander")) }.body<MemberDto>().canCall)

        assertEquals(HttpStatusCode.Created, client.ring(home.alexToken, home.kim).status)
        val targets = client.get("/calls/targets") { auth(home.alexToken) }.body<CallTargetsResponse>().memberIds
        assertEquals(setOf(home.sam, home.alex, home.kim), targets.toSet())
    }

    @Test
    fun `one call per 30 seconds per caller`() = api { client, home ->
        assertEquals(HttpStatusCode.Created, client.ring(home.samToken, home.alex).status)
        now = now.plusSeconds(20)
        val tooSoon = client.ring(home.samToken, home.kim)
        assertEquals(HttpStatusCode.TooManyRequests, tooSoon.status)
        assertTrue("10 s" in tooSoon.body<ErrorResponse>().error)
        now = now.plusSeconds(10)
        assertEquals(HttpStatusCode.Created, client.ring(home.samToken, home.kim).status)
    }

    @Test
    fun `bad calls are refused`() = api { client, home ->
        assertEquals(HttpStatusCode.BadRequest, client.ring(home.samToken).status)
        assertEquals(HttpStatusCode.BadRequest, client.ring(home.samToken, home.sam).status)
        assertEquals(HttpStatusCode.BadRequest, client.ring(home.samToken, UUID.randomUUID().toString()).status)
        assertEquals(HttpStatusCode.BadRequest, client.ring(home.samToken, home.alex, message = "  ").status)
        assertEquals(HttpStatusCode.BadRequest, client.ring(home.samToken, home.alex, message = "x".repeat(101)).status)
        assertEquals(emptyList(), sent)
    }

    @Test
    fun `an answer goes to the caller and the answerer's other phones`() = api { client, home ->
        val call = client.ring(home.samToken, home.alex, home.kim).body<CallDto>()
        sent.clear()
        val answered = client.answer(home.alexToken, call.id, "COMING").body<CallDto>()
        assertEquals(listOf("COMING", null), answered.targets.map { it.answer })
        // Sam's phone; Alex's only phone sent the answer, so it isn't pushed.
        assertEquals(listOf(Push.CALL_ANSWERED to setOf("fcm-sam")), sent)

        assertEquals(HttpStatusCode.BadRequest, client.answer(home.kimToken, call.id, "MAYBE").status)
        // The caller wasn't rung, so can't answer.
        assertEquals(HttpStatusCode.NotFound, client.answer(home.samToken, call.id, "CANT").status)
        // Too late to answer after 10 minutes.
        now = now.plus(Duration.ofMinutes(11))
        assertEquals(HttpStatusCode.NotFound, client.answer(home.kimToken, call.id, "CANT").status)
    }

    @Test
    fun `only the caller and the members rung see a call`() = api { client, home ->
        val call = client.ring(home.samToken, home.alex).body<CallDto>()
        assertEquals(HttpStatusCode.OK, client.get("/calls/${call.id}") { auth(home.samToken) }.status)
        assertEquals("Come downstairs", client.get("/calls/${call.id}") { auth(home.alexToken) }.body<CallDto>().message)
        assertEquals(HttpStatusCode.NotFound, client.get("/calls/${call.id}") { auth(home.kimToken) }.status)
    }

    @Test
    fun `deleting the household deletes its calls`() = api { client, home ->
        assertEquals(HttpStatusCode.Created, client.ring(home.samToken, home.alex).status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/household") { auth(home.samToken); header(ADMIN_PIN_HEADER, PIN) }.status)
        db.connection.use { conn ->
            conn.createStatement().executeQuery("SELECT count(*) FROM call").use { it.next(); assertEquals(0, it.getInt(1)) }
        }
    }

    @Test
    fun `the cleanup deletes calls after 7 days`() = api { client, home ->
        val call = client.ring(home.samToken, home.alex).body<CallDto>()
        Cleanup(db).run(now.plus(Duration.ofDays(6)))
        assertEquals(HttpStatusCode.OK, client.get("/calls/${call.id}") { auth(home.alexToken) }.status)
        Cleanup(db).run(now.plus(Duration.ofDays(8)))
        assertNull(api.calls.get(UUID.randomUUID(), UUID.fromString(call.id), UUID.fromString(home.alex)))
        assertEquals(HttpStatusCode.NotFound, client.get("/calls/${call.id}") { auth(home.alexToken) }.status)
    }

    private companion object {
        const val PIN = "482193"
    }
}
