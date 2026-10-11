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
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Instant updates: which phones get a "sync now" push, and when none is sent. */
class PushApiTest {
    private lateinit var api: ApiContext
    private val db get() = ApiTest.postgres.postgresDatabase

    /** Each send, as the set of push tokens it went to. */
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

    /** Sam (admin) and Alex, each with one phone registered as push token "fcm-<name>". */
    private class Home(val sam: String, val samToken: String, val alex: String, val alexToken: String)

    private fun api(block: suspend ApplicationTestBuilder.(HttpClient, Home) -> Unit) = testApplication {
        application { module(api) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val session = client.post("/household") {
            contentType(ContentType.Application.Json)
            setBody(CreateHouseholdRequest("setup", "Home", "kitchen-table", "Europe/London", "Sam", "4821"))
        }.body<SessionResponse>()
        client.registerPush(session.token, "fcm-sam")
        val alexToken = client.join("Alex")
        client.registerPush(alexToken, "fcm-alex")
        val alex = client.approve(session.token, client.pendingIds(session.token).single()).id
        sent.clear()
        block(client, Home(session.household.adminMemberId!!, session.token, alex, alexToken))
    }

    private suspend fun HttpClient.join(name: String): String = post("/join") {
        contentType(ContentType.Application.Json)
        setBody(JoinRequest("kitchen-table", name))
    }.body<SessionResponse>().token

    private suspend fun HttpClient.registerPush(token: String, pushToken: String?): HttpResponse = put("/me/push-token") {
        contentType(ContentType.Application.Json)
        header(HttpHeaders.Authorization, "Bearer $token")
        setBody(PushTokenRequest(pushToken))
    }

    private suspend fun HttpClient.pendingIds(adminToken: String) =
        get("/me") { header(HttpHeaders.Authorization, "Bearer $adminToken") }.body<HouseholdResponse>().pendingRequestIds

    private suspend fun HttpClient.approve(adminToken: String, requestId: String) = post("/join-requests/$requestId/approve") {
        contentType(ContentType.Application.Json)
        header(HttpHeaders.Authorization, "Bearer $adminToken")
        header(ADMIN_PIN_HEADER, "4821")
        setBody(ApproveRequest())
    }.body<MemberDto>()

    private suspend fun HttpClient.sync(token: String, req: SyncRequest) = post("/v2/sync") {
        contentType(ContentType.Application.Json)
        header(HttpHeaders.Authorization, "Bearer $token")
        setBody(req)
    }.also { assertEquals(HttpStatusCode.OK, it.status) }

    private fun event(owner: String, updatedAt: Long = 1_000, id: String = UUID.randomUUID().toString()) = EventSyncDto(
        id = id, ownerMemberId = owner, title = "Swimming", weekdays = 1, createdAt = 1_000, updatedAt = updatedAt,
    )

    @Test
    fun `a sync that writes something pushes the other phones, not the sender`() = api { client, home ->
        val e = event(home.sam)
        client.sync(home.samToken, SyncRequest(home.sam, events = listOf(e)))
        assertEquals(listOf(setOf("fcm-alex")), sent)
    }

    @Test
    fun `a sync that changes nothing sends no push`() = api { client, home ->
        // Nothing pushed: the 15-minute refresh, or a sync started by a push.
        client.sync(home.alexToken, SyncRequest(home.alex))
        // An older write that loses, and the same settings again.
        val e = event(home.sam, updatedAt = 2_000)
        val settings = SettingsSyncDto(10, "BOTH", "SILENT", updatedAt = 5_000)
        client.sync(home.samToken, SyncRequest(home.sam, events = listOf(e), settings = settings))
        sent.clear()
        client.sync(home.samToken, SyncRequest(home.sam, events = listOf(e.copy(title = "Old", updatedAt = 1_500)), settings = settings))
        // A rejected row.
        client.sync(home.samToken, SyncRequest(home.sam, events = listOf(event(home.sam).copy(weekdays = 0))))
        assertEquals(emptyList(), sent)
    }

    @Test
    fun `chore changes push the other phones`() = api { client, home ->
        val response = client.put("/chores/${UUID.randomUUID()}") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${home.alexToken}")
            setBody(ChoreRequest("Bins", weekdays = 1, completion = ChoreCompletion.ANY, assigneeIds = listOf(home.sam)))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf(setOf("fcm-sam")), sent)
    }

    @Test
    fun `a join request pushes the admin, and approving pushes the new phone`() = api { client, home ->
        val kimToken = client.join("Kim")
        assertEquals(listOf(setOf("fcm-sam")), sent)
        // A pending phone can register, so it hears about its approval.
        assertEquals(HttpStatusCode.NoContent, client.registerPush(kimToken, "fcm-kim").status)
        sent.clear()
        client.approve(home.samToken, client.pendingIds(home.samToken).single())
        // Kim's phone moves on; Alex's phone gets the new member. Sam's phone made the change.
        assertEquals(listOf(setOf("fcm-kim"), setOf("fcm-alex", "fcm-kim")), sent)
    }

    @Test
    fun `a rejected or removed phone is pushed once, then keeps no token`() = api { client, home ->
        val kimToken = client.join("Kim")
        client.registerPush(kimToken, "fcm-kim")
        sent.clear()
        val requestId = client.pendingIds(home.samToken).single()
        client.post("/join-requests/$requestId/reject") {
            header(HttpHeaders.Authorization, "Bearer ${home.samToken}")
            header(ADMIN_PIN_HEADER, "4821")
        }
        assertEquals(listOf(setOf("fcm-kim")), sent)

        sent.clear()
        client.delete("/members/${home.alex}") {
            header(HttpHeaders.Authorization, "Bearer ${home.samToken}")
            header(ADMIN_PIN_HEADER, "4821")
        }
        assertEquals(setOf("fcm-alex"), sent.first())
        assertEquals(emptyList(), PushStore(db).householdTokens(api.store.getHousehold()!!.id, null) - "fcm-sam")
    }

    @Test
    fun `a push token moves to the newest phone that registers it`() = api { client, home ->
        // The same token from a second device (a reinstall that rejoined): only the newest keeps it.
        client.registerPush(home.alexToken, "fcm-sam")
        client.sync(home.alexToken, SyncRequest(home.alex, events = listOf(event(home.alex))))
        assertEquals(emptyList(), sent)
    }
}
