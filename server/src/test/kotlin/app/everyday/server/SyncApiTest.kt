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

    /** A household with Sam (admin), Alex and Kim, and a device token. */
    private class Home(val token: String, val sam: String, val alex: String, val kim: String)

    private fun api(block: suspend ApplicationTestBuilder.(HttpClient, Home) -> Unit) = testApplication {
        application { module(api) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val session = client.post("/household") {
            contentType(ContentType.Application.Json)
            setBody(CreateHouseholdRequest("setup", "Home", "kitchen-table", "Europe/London", "Sam", "4821"))
        }.body<SessionResponse>()
        suspend fun add(name: String) = client.post("/members") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${session.token}")
            header(ADMIN_PIN_HEADER, "4821")
            setBody(MemberRequest(name))
        }.body<MemberDto>().id
        block(client, Home(session.token, session.household.adminMemberId!!, add("Alex"), add("Kim")))
    }

    private suspend fun HttpClient.sync(home: Home, req: SyncRequest) = post("/sync") {
        contentType(ContentType.Application.Json)
        header(HttpHeaders.Authorization, "Bearer ${home.token}")
        setBody(req)
    }

    private suspend fun HttpClient.syncOk(home: Home, req: SyncRequest): SyncResponse {
        val response = sync(home, req)
        assertEquals(HttpStatusCode.OK, response.status)
        return response.body()
    }

    private fun reminder(owner: String, updatedAt: Long = 1_000, id: String = UUID.randomUUID().toString(), vararg recipients: String) =
        ReminderSyncDto(
            id = id,
            ownerMemberId = owner,
            message = "Water plants",
            mode = "TIMED",
            weekdays = 127,
            times = listOf(480, 1200),
            recipients = recipients.map { RecipientDto(it) },
            createdAt = 1_000,
            updatedAt = updatedAt,
        )

    @Test
    fun `reminders push and come back in the snapshot`() = api { client, home ->
        val r = reminder(home.sam)
        val out = client.syncOk(home, SyncRequest(home.sam, reminders = listOf(r)))
        assertTrue(out.rejected.isEmpty())
        val back = out.reminders.single()
        assertEquals(r.id, back.id)
        assertEquals(listOf(480, 1200), back.times)
        assertEquals(1_000, back.updatedAt)
        // Alex sees nothing of Sam's personal reminder.
        assertTrue(client.syncOk(home, SyncRequest(home.alex)).reminders.isEmpty())
    }

    @Test
    fun `an older write loses`() = api { client, home ->
        val r = reminder(home.sam, updatedAt = 2_000)
        client.syncOk(home, SyncRequest(home.sam, reminders = listOf(r)))
        val stale = r.copy(message = "Old text", updatedAt = 1_500)
        assertEquals("Water plants", client.syncOk(home, SyncRequest(home.sam, reminders = listOf(stale))).reminders.single().message)
        val newer = r.copy(message = "New text", times = listOf(600), updatedAt = 2_500)
        val out = client.syncOk(home, SyncRequest(home.sam, reminders = listOf(newer))).reminders.single()
        assertEquals("New text", out.message)
        assertEquals(listOf(600), out.times)
    }

    @Test
    fun `a bad row is rejected while the rest of the batch applies`() = api { client, home ->
        val good = reminder(home.sam)
        val badWindow = reminder(home.sam).copy(mode = "PERSISTENT", times = emptyList(), windows = listOf(WindowDto(600, 500)))
        val notMine = reminder(home.alex)
        val out = client.syncOk(home, SyncRequest(home.sam, reminders = listOf(badWindow, good, notMine)))
        assertEquals(setOf(badWindow.id, notMine.id), out.rejected.toSet())
        assertEquals(listOf(good.id), out.reminders.map { it.id })
    }

    @Test
    fun `tombstones sync and keep the reminder deleted`() = api { client, home ->
        val r = reminder(home.sam)
        client.syncOk(home, SyncRequest(home.sam, reminders = listOf(r)))
        val gone = client.syncOk(home, SyncRequest(home.sam, reminders = listOf(r.copy(deleted = true, updatedAt = 3_000))))
        assertTrue(gone.reminders.single().deleted)
    }

    @Test
    fun `unknown member is refused`() = api { client, home ->
        assertEquals(HttpStatusCode.BadRequest, client.sync(home, SyncRequest(UUID.randomUUID().toString())).status)
        assertEquals(HttpStatusCode.BadRequest, client.sync(home, SyncRequest("nope")).status)
    }

    @Test
    fun `sharing invites members who opt in or out, and only the owner edits`() = api { client, home ->
        val r = reminder(home.sam, 1_000, UUID.randomUUID().toString(), home.alex, home.kim)
        val owner = client.syncOk(home, SyncRequest(home.sam, reminders = listOf(r))).reminders.single()
        assertEquals(setOf(home.alex to "pending", home.kim to "pending"), owner.recipients.map { it.memberId to it.status }.toSet())

        // Alex sees the invite and accepts; Alex can't edit the reminder.
        assertEquals(r.id, client.syncOk(home, SyncRequest(home.alex)).reminders.single().id)
        val accepted = client.syncOk(
            home,
            SyncRequest(
                home.alex,
                recipientStatuses = listOf(RecipientStatusDto(r.id, "accepted", 2_000)),
                reminders = listOf(r.copy(message = "Hijacked", updatedAt = 5_000)),
            ),
        )
        assertEquals(listOf(r.id), accepted.rejected)
        assertEquals("Water plants", accepted.reminders.single().message)
        assertEquals("accepted", accepted.reminders.single().recipients.first { it.memberId == home.alex }.status)

        // Owner removes Kim's invite; Kim no longer sees the reminder.
        client.syncOk(home, SyncRequest(home.sam, reminders = listOf(r.copy(recipients = listOf(RecipientDto(home.alex)), updatedAt = 3_000))))
        assertTrue(client.syncOk(home, SyncRequest(home.kim)).reminders.isEmpty())
        // Alex's answer survived the owner's edit.
        val alexView = client.syncOk(home, SyncRequest(home.alex)).reminders.single()
        assertEquals(listOf(home.alex to "accepted"), alexView.recipients.map { it.memberId to it.status })
    }

    @Test
    fun `state and settings are per member with last write wins`() = api { client, home ->
        val r = reminder(home.sam, 1_000, UUID.randomUUID().toString(), home.alex)
        client.syncOk(home, SyncRequest(home.sam, reminders = listOf(r)))
        client.syncOk(home, SyncRequest(home.sam, states = listOf(StateSyncDto(r.id, doneOn = "2026-10-08", updatedAt = 2_000))))
        // Alex, an invitee, has their own state; Kim, not invited, is rejected.
        val alex = client.syncOk(home, SyncRequest(home.alex, states = listOf(StateSyncDto(r.id, snoozeUntil = 9_000, updatedAt = 2_000))))
        assertNull(alex.states.single().doneOn)
        assertEquals(listOf("state:${r.id}"), client.syncOk(home, SyncRequest(home.kim, states = listOf(StateSyncDto(r.id, updatedAt = 1)))).rejected)

        val stale = client.syncOk(home, SyncRequest(home.sam, states = listOf(StateSyncDto(r.id, doneOn = null, updatedAt = 1_500))))
        assertEquals("2026-10-08", stale.states.single().doneOn)

        val settings = client.syncOk(home, SyncRequest(home.sam, settings = SettingsSyncDto(25, "VIBRATE", "SILENT", 5_000))).settings!!
        assertEquals(25, settings.snoozeMinutes)
        assertEquals(listOf("settings"), client.syncOk(home, SyncRequest(home.sam, settings = SettingsSyncDto(0, "LOUD", "SILENT", 6_000))).rejected)
    }

    @Test
    fun `chore reminders can't be shared and need the owner assigned`() = api { client, home ->
        val choreId = UUID.randomUUID().toString()
        client.put("/chores/$choreId") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${home.token}")
            setBody(ChoreRequest("Bins", weekdays = 127, completion = ChoreCompletion.ANY, assigneeIds = listOf(home.sam)))
        }
        val ok = reminder(home.sam).copy(choreId = choreId)
        val shared = reminder(home.sam, 1_000, UUID.randomUUID().toString(), home.alex).copy(choreId = choreId)
        val unassigned = reminder(home.alex).copy(choreId = choreId)
        assertEquals(listOf(shared.id), client.syncOk(home, SyncRequest(home.sam, reminders = listOf(ok, shared))).rejected)
        assertEquals(listOf(unassigned.id), client.syncOk(home, SyncRequest(home.alex, reminders = listOf(unassigned))).rejected)

        // The chore timetable shows the assignee's reminder times (480 and 1200 from the helper).
        val chores = client.get("/chores") { header(HttpHeaders.Authorization, "Bearer ${home.token}") }.body<ChoresResponse>()
        assertEquals(listOf(480, 1200), chores.slots.filter { it.memberId == home.sam }.map { it.startMinute })
        // Paused reminders don't show.
        client.syncOk(home, SyncRequest(home.sam, reminders = listOf(ok.copy(active = false, updatedAt = 2_000))))
        assertTrue(client.get("/chores") { header(HttpHeaders.Authorization, "Bearer ${home.token}") }.body<ChoresResponse>().slots.isEmpty())
    }

    @Test
    fun `a removed member's shared reminder lives on until every invitee removes it`() = api { client, home ->
        val personal = reminder(home.alex)
        val shared = reminder(home.alex, 1_000, UUID.randomUUID().toString(), home.sam, home.kim)
        client.syncOk(home, SyncRequest(home.alex, reminders = listOf(personal, shared)))
        client.syncOk(home, SyncRequest(home.sam, recipientStatuses = listOf(RecipientStatusDto(shared.id, "accepted", 2_000))))
        // Sam's invite to Alex is removed along with Alex.
        val samsShared = reminder(home.sam, 1_000, UUID.randomUUID().toString(), home.alex)
        client.syncOk(home, SyncRequest(home.sam, reminders = listOf(samsShared)))

        client.delete("/members/${home.alex}") {
            header(HttpHeaders.Authorization, "Bearer ${home.token}")
            header(ADMIN_PIN_HEADER, "4821")
        }

        val samView = client.syncOk(home, SyncRequest(home.sam)).reminders.associateBy { it.id }
        assertTrue(!samView.getValue(shared.id).deleted)
        assertTrue(samView.getValue(samsShared.id).recipients.isEmpty())

        // Sam and Kim remove it for themselves (decline); then it's deleted.
        client.syncOk(home, SyncRequest(home.sam, recipientStatuses = listOf(RecipientStatusDto(shared.id, "declined", 3_000))))
        assertTrue(!client.syncOk(home, SyncRequest(home.kim)).reminders.single().deleted)
        val last = client.syncOk(home, SyncRequest(home.kim, recipientStatuses = listOf(RecipientStatusDto(shared.id, "declined", 3_000))))
        assertTrue(last.reminders.single().deleted)
    }
}
