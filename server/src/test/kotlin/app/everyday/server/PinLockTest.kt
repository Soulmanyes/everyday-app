package app.everyday.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
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
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Admin PIN lockouts (V17), what pending phones see, and the limit on waiting phones. */
class PinLockTest {
    private lateinit var api: ApiContext
    private val db get() = ApiTest.postgres.postgresDatabase
    private var now = Instant.parse("2026-10-14T12:00:00Z")
    private val sent = mutableListOf<Set<String>>()
    private val pin = "482193"

    @BeforeTest
    fun setUp() {
        db.connection.use { it.createStatement().execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;") }
        migrate(db)
        sent.clear()
        api = newApi()
    }

    /** A fresh API, as after a restart: in-memory counters start over, the database keeps its locks. */
    private fun newApi() = ApiContext(
        HouseholdStore(db), ChoreStore(db), SyncStore(db), Secrets(iterations = 1_000), AttemptLimiter(clock = { now }),
        setupToken = "setup", clock = { now },
        // These tests send many requests from one address: the per-IP rate limits are tested in ApiTest.
        rateLimits = RateLimits(secrets = 1_000, admin = 1_000, other = 10_000),
        push = Push(PushStore(db)) { tokens -> if (tokens.isNotEmpty()) sent += tokens.toSet() },
    )

    private fun api(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application { module(api) }
        block(createClient { install(ContentNegotiation) { json() } })
    }

    private fun HttpRequestBuilder.json(body: Any) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun HttpRequestBuilder.admin(token: String, pin: String = this@PinLockTest.pin) {
        header(HttpHeaders.Authorization, "Bearer $token")
        header(ADMIN_PIN_HEADER, pin)
    }

    private suspend fun HttpClient.createHousehold(): SessionResponse =
        post("/household") { json(CreateHouseholdRequest("Home", "Europe/London", "Sam", pin, setupToken = "setup")) }
            .also { assertEquals(HttpStatusCode.Created, it.status) }.body()

    private suspend fun HttpClient.join(code: String, name: String = "Alex", adminPin: String? = null): HttpResponse =
        post("/join") { json(JoinRequest(code, name, adminPin = adminPin)) }

    private suspend fun HttpClient.me(token: String): HouseholdResponse =
        get("/me") { header(HttpHeaders.Authorization, "Bearer $token") }.body()

    private suspend fun HttpClient.verify(token: String, pin: String = this@PinLockTest.pin): HttpStatusCode =
        post("/admin/verify") { admin(token, pin) }.status

    /** Alex's approved phone token. */
    private suspend fun HttpClient.joinApproved(home: SessionResponse, name: String = "Alex"): String {
        val token = join(home.joinCode!!, name).body<SessionResponse>().token
        val id = me(home.token).pendingRequestIds.single()
        post("/join-requests/$id/approve") { admin(home.token); json(ApproveRequest()) }
        return token
    }

    @Test
    fun `wrong PINs while joining lock only joining as admin, longer each time`() = api { client ->
        val home = client.createHousehold()
        val code = home.joinCode!!
        repeat(10) { assertEquals(HttpStatusCode.Forbidden, client.join(code, adminPin = "000000").status) }
        // Even the right PIN waits now, but the admin's phone and ordinary joining still work.
        assertEquals(HttpStatusCode.TooManyRequests, client.join(code, adminPin = pin).status)
        assertEquals(HttpStatusCode.NoContent, client.verify(home.token))
        assertEquals(HttpStatusCode.OK, client.join(code, "Kim").status)

        // The second lockout within a day lasts an hour, the third a day.
        now = now.plus(Duration.ofMinutes(16))
        repeat(10) { assertEquals(HttpStatusCode.Forbidden, client.join(code, adminPin = "000000").status) }
        now = now.plus(Duration.ofMinutes(59))
        assertEquals(HttpStatusCode.TooManyRequests, client.join(code, adminPin = pin).status)
        now = now.plus(Duration.ofMinutes(2))
        repeat(10) { client.join(code, adminPin = "000000") }
        val third = client.join(code, adminPin = pin)
        assertEquals(HttpStatusCode.TooManyRequests, third.status)
        assertEquals("Too many wrong attempts. Try again in 24 h.", third.body<ErrorResponse>().error)
        now = now.plus(Duration.ofHours(23))
        assertEquals(HttpStatusCode.TooManyRequests, client.join(code, adminPin = pin).status)

        // After it ends, the right PIN gets in and starts the count over.
        now = now.plus(Duration.ofHours(2))
        assertEquals(HttpStatusCode.OK, client.join(code, adminPin = pin).status)
        repeat(9) { client.join(code, adminPin = "000000") }
        assertEquals(HttpStatusCode.OK, client.join(code, adminPin = pin).status)
    }

    @Test
    fun `wrong PINs from a member's phone lock only that phone, and survive a restart`() = api { client ->
        val home = client.createHousehold()
        val alex = client.joinApproved(home)
        repeat(10) { assertEquals(HttpStatusCode.Forbidden, client.verify(alex, "000000")) }
        assertEquals(HttpStatusCode.TooManyRequests, client.verify(alex, pin))
        assertEquals(HttpStatusCode.NoContent, client.verify(home.token))
        assertEquals(HttpStatusCode.OK, client.join(home.joinCode!!, adminPin = pin).status)

        api = newApi()
        api { restarted -> assertEquals(HttpStatusCode.TooManyRequests, restarted.verify(alex, pin)) }
    }

    @Test
    fun `a PIN lockout tells the admin's phones, and only them`() = api { client ->
        val home = client.createHousehold()
        val alex = client.joinApproved(home)
        client.put("/me/push-token") { header(HttpHeaders.Authorization, "Bearer ${home.token}"); json(PushTokenRequest("fcm-sam")) }
        assertNull(client.me(home.token).pinLockedAt)
        sent.clear()

        repeat(9) { client.join(home.joinCode!!, adminPin = "000000") }
        assertTrue(sent.isEmpty())
        client.join(home.joinCode!!, adminPin = "000000")
        assertEquals(listOf(setOf("fcm-sam")), sent)
        assertEquals(now.toEpochMilli(), client.me(home.token).pinLockedAt)
        assertNull(client.me(alex).pinLockedAt)
    }

    @Test
    fun `pending phones see no member names until they're approved`() = api { client ->
        val home = client.createHousehold()
        val joined = client.join(home.joinCode!!).body<SessionResponse>()
        assertEquals("pending", joined.deviceStatus)
        assertTrue(joined.members.isEmpty())
        assertTrue(client.me(joined.token).members.isEmpty())

        val viaInvite = client.post("/member-invites") { admin(home.token) }.body<CreatedInviteResponse>().code
        assertTrue(client.join(viaInvite, "Kim").body<SessionResponse>().members.isEmpty())

        val id = client.me(home.token).pendingRequestIds.first()
        client.post("/join-requests/$id/approve") { admin(home.token); json(ApproveRequest()) }
        assertEquals(listOf("Alex", "Sam"), client.me(joined.token).members.map { it.displayName }.sorted())
    }

    @Test
    fun `at most 20 phones wait for approval at once`() = api { client ->
        val home = client.createHousehold()
        repeat(HouseholdStore.MAX_PENDING) { assertEquals(HttpStatusCode.OK, client.join(home.joinCode!!, "Guest $it").status) }
        assertEquals(HttpStatusCode.Conflict, client.join(home.joinCode!!, "One more").status)
        // Turning one down makes room.
        val id = client.me(home.token).pendingRequestIds.first()
        client.post("/join-requests/$id/reject") { admin(home.token) }
        assertEquals(HttpStatusCode.OK, client.join(home.joinCode!!, "One more").status)
    }

    @Test
    fun `new households need a 6 to 8 digit PIN, older 4-digit ones keep working`() = api { client ->
        val short = client.post("/household") { json(CreateHouseholdRequest("Home", "Europe/London", "Sam", "4821", setupToken = "setup")) }
        assertEquals(HttpStatusCode.BadRequest, short.status)
        assertEquals("The admin PIN must be 6 to 8 digits.", short.body<ErrorResponse>().error)

        val home = client.createHousehold()
        val oldHash = Secrets(iterations = 1_000).hash("4821")
        db.connection.use { conn ->
            conn.prepareStatement("UPDATE household SET admin_pin_hash = ?").run { setString(1, oldHash); executeUpdate() }
        }
        assertEquals(HttpStatusCode.NoContent, client.verify(home.token, "4821"))
    }
}
