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
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApiTest {
    private lateinit var api: ApiContext
    private var now = Instant.parse("2026-10-08T12:00:00Z")

    @BeforeTest
    fun setUp() {
        postgres.postgresDatabase.connection.use { it.createStatement().execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;") }
        migrate(postgres.postgresDatabase)
        api = ApiContext(
            store = HouseholdStore(postgres.postgresDatabase),
            chores = ChoreStore(postgres.postgresDatabase),
            sync = SyncStore(postgres.postgresDatabase),
            secrets = Secrets(iterations = 1_000),
            limiter = AttemptLimiter(maxFailures = 5, clock = { now }),
            setupToken = SETUP,
            clock = { now },
        )
    }

    private fun api(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application { module(api) }
        val client = createClient { install(ContentNegotiation) { json() } }
        block(client)
    }

    private suspend fun HttpClient.postJson(path: String, body: Any, token: String? = null, pin: String? = null): HttpResponse =
        post(path) {
            contentType(ContentType.Application.Json)
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            pin?.let { header(ADMIN_PIN_HEADER, it) }
            setBody(body)
        }

    private fun createRequest(token: String = SETUP) = CreateHouseholdRequest(
        setupToken = token,
        householdName = "Home",
        householdCode = CODE,
        timezone = "Europe/London",
        adminName = "Sam",
        adminPin = PIN,
        deviceLabel = "Phone",
    )

    private suspend fun HttpClient.createHousehold(): SessionResponse {
        val response = postJson("/household", createRequest())
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body()
    }

    @Test
    fun `household can be created once with the setup code`() = api { client ->
        assertFalse(client.get("/status").body<StatusResponse>().householdExists)
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/household", createRequest(token = "wrong")).status)

        val session = client.createHousehold()
        assertEquals("Home", session.household.name)
        assertEquals(listOf("Sam"), session.members.map { it.displayName })
        assertEquals(session.members.single().id, session.household.adminMemberId)
        assertTrue(client.get("/status").body<StatusResponse>().householdExists)

        assertEquals(HttpStatusCode.Conflict, client.postJson("/household", createRequest()).status)
    }

    @Test
    fun `creation is refused when no setup token is configured`() {
        api = ApiContext(api.store, api.chores, api.sync, api.secrets, api.limiter, setupToken = null)
        api { client -> assertEquals(HttpStatusCode.Forbidden, client.postJson("/household", createRequest()).status) }
    }

    @Test
    fun `invalid household details are rejected`() = api { client ->
        val short = createRequest().copy(householdCode = "12345")
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/household", short).status)
        val badPin = createRequest().copy(adminPin = "12ab")
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/household", badPin).status)
        val badZone = createRequest().copy(timezone = "Mars/Base")
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/household", badZone).status)
    }

    @Test
    fun `joining needs the right household code`() = api { client ->
        assertEquals(HttpStatusCode.NotFound, client.postJson("/join", JoinRequest(CODE)).status)
        client.createHousehold()
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/join", JoinRequest("not-the-code")).status)

        val joined = client.postJson("/join", JoinRequest(CODE, "Tablet"))
        assertEquals(HttpStatusCode.OK, joined.status)
        val token = joined.body<SessionResponse>().token

        val me = client.get("/me") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, me.status)
        assertEquals("Home", me.body<HouseholdResponse>().household.name)
    }

    @Test
    fun `requests without a valid token are unauthorized`() = api { client ->
        client.createHousehold()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/me").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/me") { header(HttpHeaders.Authorization, "Bearer nope") }.status)
    }

    @Test
    fun `leaving revokes the token`() = api { client ->
        val token = client.createHousehold().token
        assertEquals(HttpStatusCode.NoContent, client.delete("/device") { header(HttpHeaders.Authorization, "Bearer $token") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/me") { header(HttpHeaders.Authorization, "Bearer $token") }.status)
    }

    @Test
    fun `member changes need the admin pin`() = api { client ->
        val session = client.createHousehold()
        val token = session.token
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/members", MemberRequest("Alex"), token).status)
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/members", MemberRequest("Alex"), token, pin = "0000").status)
        assertEquals(HttpStatusCode.NoContent, client.postJson("/admin/verify", MemberRequest("x"), token, pin = PIN).status)

        val alex = client.postJson("/members", MemberRequest("Alex"), token, PIN)
        assertEquals(HttpStatusCode.Created, alex.status)
        val alexId = alex.body<MemberDto>().id
        assertEquals(HttpStatusCode.Conflict, client.postJson("/members", MemberRequest("alex"), token, PIN).status)

        val renamed = client.put("/members/$alexId") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            header(ADMIN_PIN_HEADER, PIN)
            setBody(MemberRequest("Alexis"))
        }
        assertEquals("Alexis", renamed.body<MemberDto>().displayName)

        val adminId = session.household.adminMemberId!!
        fun deleteMember(id: String) = runBlocking {
            client.delete("/members/$id") {
                header(HttpHeaders.Authorization, "Bearer $token")
                header(ADMIN_PIN_HEADER, PIN)
            }.status
        }
        assertEquals(HttpStatusCode.BadRequest, deleteMember(adminId))
        assertEquals(HttpStatusCode.NoContent, deleteMember(alexId))
        assertEquals(HttpStatusCode.NotFound, deleteMember(alexId))

        val members = client.get("/me") { header(HttpHeaders.Authorization, "Bearer $token") }.body<HouseholdResponse>().members
        assertEquals(listOf("Sam"), members.map { it.displayName })
        // A deleted member's name can be used again.
        assertEquals(HttpStatusCode.Created, client.postJson("/members", MemberRequest("Alexis"), token, PIN).status)
    }

    @Test
    fun `too many wrong codes lock joining, even with the right code`() = api { client ->
        client.createHousehold()
        repeat(5) { assertEquals(HttpStatusCode.Forbidden, client.postJson("/join", JoinRequest("wrong-code")).status) }
        assertEquals(HttpStatusCode.TooManyRequests, client.postJson("/join", JoinRequest(CODE)).status)
        now = now.plusSeconds(16 * 60)
        assertEquals(HttpStatusCode.OK, client.postJson("/join", JoinRequest(CODE)).status)
    }

    private suspend fun HttpClient.putChore(token: String, id: String, req: ChoreRequest): HttpResponse = put("/chores/$id") {
        contentType(ContentType.Application.Json)
        header(HttpHeaders.Authorization, "Bearer $token")
        setBody(req)
    }

    private suspend fun HttpClient.chores(token: String): ChoresResponse =
        get("/chores") { header(HttpHeaders.Authorization, "Bearer $token") }.body()

    private suspend fun HttpClient.addMember(token: String, name: String): String =
        postJson("/members", MemberRequest(name), token, PIN).body<MemberDto>().id

    private fun chore(vararg assignees: String, completion: ChoreCompletion = ChoreCompletion.ANY) =
        ChoreRequest("Bins", notes = "  ", weekdays = 0b0000101, completion = completion, assigneeIds = assignees.toList())

    @Test
    fun `chores can be created, edited and deleted by any member`() = api { client ->
        val session = client.createHousehold()
        val token = session.token
        val sam = session.household.adminMemberId!!
        val alex = client.addMember(token, "Alex")
        val id = UUID.randomUUID().toString()

        assertEquals(HttpStatusCode.Unauthorized, client.get("/chores").status)
        val created = client.putChore(token, id, chore(sam, alex)).body<ChoresResponse>()
        assertEquals("2026-10-08", created.today)
        val dto = created.chores.single()
        assertEquals(id, dto.id)
        assertEquals(null, dto.notes)
        assertEquals(listOf(sam, alex), dto.assigneeIds)

        val edited = client.putChore(token, id, chore(alex).copy(title = "Take out bins")).body<ChoresResponse>()
        assertEquals("Take out bins", edited.chores.single().title)
        assertEquals(listOf(alex), edited.chores.single().assigneeIds)
        // Re-assigning a previously removed member works.
        assertEquals(listOf(sam, alex), client.putChore(token, id, chore(sam, alex)).body<ChoresResponse>().chores.single().assigneeIds)

        val deleted = client.delete("/chores/$id") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, deleted.status)
        assertTrue(deleted.body<ChoresResponse>().chores.isEmpty())
        assertEquals(HttpStatusCode.NotFound, client.putChore(token, id, chore(sam)).status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/chores/$id") { header(HttpHeaders.Authorization, "Bearer $token") }.status)
    }

    @Test
    fun `invalid chores are rejected`() = api { client ->
        val session = client.createHousehold()
        val token = session.token
        val sam = session.household.adminMemberId!!
        val id = UUID.randomUUID().toString()
        assertEquals(HttpStatusCode.BadRequest, client.putChore(token, id, chore()).status)
        assertEquals(HttpStatusCode.BadRequest, client.putChore(token, id, chore(sam).copy(title = " ")).status)
        assertEquals(HttpStatusCode.BadRequest, client.putChore(token, id, chore(sam).copy(weekdays = 0)).status)
        assertEquals(HttpStatusCode.BadRequest, client.putChore(token, id, chore(sam).copy(weekdays = 128)).status)
        assertEquals(HttpStatusCode.BadRequest, client.putChore(token, id, chore(UUID.randomUUID().toString())).status)
        assertEquals(HttpStatusCode.BadRequest, client.putChore(token, "not-a-uuid", chore(sam)).status)
        assertTrue(client.chores(token).chores.isEmpty())
    }

    @Test
    fun `an ANY chore is done once for everyone, and anyone can undo it`() = api { client ->
        val session = client.createHousehold()
        val token = session.token
        val sam = session.household.adminMemberId!!
        val alex = client.addMember(token, "Alex")
        val id = UUID.randomUUID().toString()
        client.putChore(token, id, chore(sam))

        // Alex isn't assigned but can still mark it done.
        val done = client.postJson("/chores/$id/done", ChoreDoneRequest(alex), token).body<ChoresResponse>()
        assertEquals(listOf(ChoreCompletionDto(id, alex, "2026-10-08")), done.completions)
        // A second mark doesn't add another completion.
        assertEquals(1, client.postJson("/chores/$id/done", ChoreDoneRequest(sam), token).body<ChoresResponse>().completions.size)

        assertTrue(client.postJson("/chores/$id/undo", ChoreDoneRequest(sam), token).body<ChoresResponse>().completions.isEmpty())
    }

    @Test
    fun `an EACH chore is ticked per assignee`() = api { client ->
        val session = client.createHousehold()
        val token = session.token
        val sam = session.household.adminMemberId!!
        val alex = client.addMember(token, "Alex")
        val kim = client.addMember(token, "Kim")
        val id = UUID.randomUUID().toString()
        client.putChore(token, id, chore(sam, alex, completion = ChoreCompletion.EACH))

        assertEquals(HttpStatusCode.BadRequest, client.postJson("/chores/$id/done", ChoreDoneRequest(kim), token).status)
        client.postJson("/chores/$id/done", ChoreDoneRequest(sam), token)
        val both = client.postJson("/chores/$id/done", ChoreDoneRequest(alex), token).body<ChoresResponse>()
        assertEquals(setOf(sam, alex), both.completions.map { it.memberId }.toSet())

        // Undo only removes the caller's own part.
        val undone = client.postJson("/chores/$id/undo", ChoreDoneRequest(alex), token).body<ChoresResponse>()
        assertEquals(listOf(sam), undone.completions.map { it.memberId })

        // Switching the type starts today over.
        assertTrue(client.putChore(token, id, chore(sam, alex)).body<ChoresResponse>().completions.isEmpty())
    }

    @Test
    fun `today follows the household timezone`() = api { client ->
        val session = client.createHousehold()
        val token = session.token
        val sam = session.household.adminMemberId!!
        val id = UUID.randomUUID().toString()
        client.putChore(token, id, chore(sam))

        // 23:30 UTC is already 00:30 the next day in London (BST).
        now = Instant.parse("2026-10-08T23:30:00Z")
        val done = client.postJson("/chores/$id/done", ChoreDoneRequest(sam), token).body<ChoresResponse>()
        assertEquals("2026-10-09", done.today)
        assertEquals("2026-10-09", done.completions.single().doneOn)

        now = Instant.parse("2026-10-09T23:30:00Z")
        val nextDay = client.chores(token)
        assertEquals("2026-10-10", nextDay.today)
        assertTrue(nextDay.completions.isEmpty())
    }

    @Test
    fun `removing a member removes their chore assignments`() = api { client ->
        val session = client.createHousehold()
        val token = session.token
        val sam = session.household.adminMemberId!!
        val alex = client.addMember(token, "Alex")
        val id = UUID.randomUUID().toString()
        client.putChore(token, id, chore(sam, alex))

        client.delete("/members/$alex") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(ADMIN_PIN_HEADER, PIN)
        }
        assertEquals(listOf(sam), client.chores(token).chores.single().assigneeIds)
    }

    companion object {
        const val SETUP = "setup-secret"
        const val CODE = "kitchen-table"
        const val PIN = "4821"

        val postgres: EmbeddedPostgres by lazy { EmbeddedPostgres.start() }
    }
}
