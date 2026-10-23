package app.everyday.server

import com.google.auth.oauth2.GoogleCredentials
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import javax.sql.DataSource

/**
 * Instant updates (Phase 8.5): after a change is committed, the affected phones get a data-only FCM
 * "sync now" push and then fetch the change themselves, so no household data goes through Google.
 * The phones' 15-minute refresh stays as the fallback.
 */
class Push(val store: PushStore, private val sender: PushSender) {

    /** Every approved phone in the household except [exceptDevice], the phone that made the change. */
    fun household(householdId: UUID, exceptDevice: UUID?) = sender.send(store.householdTokens(householdId, exceptDevice))

    /** The admin's phones: a new join request. */
    fun admin(householdId: UUID) = sender.send(store.adminTokens(householdId))

    /** Sends to tokens read before their phones were revoked (a rejected request, a removed member). */
    fun tokens(tokens: List<String>) = sender.send(tokens)

    /**
     * Rings the [members]' phones (Phase 12). Like a sync push it carries only an id: the phone fetches the
     * call itself, so no names or messages go through Google.
     */
    fun ring(householdId: UUID, members: Collection<UUID>, callId: UUID) =
        sender.sendCall(store.membersTokens(householdId, members, exceptDevice = null), CALL, callId)

    /** Someone answered: the caller's phones show it, and the answerer's other phones stop ringing. */
    fun callAnswered(householdId: UUID, members: Collection<UUID>, exceptDevice: UUID, callId: UUID) =
        sender.sendCall(store.membersTokens(householdId, members, exceptDevice), CALL_ANSWERED, callId)

    companion object {
        const val CALL = "call"
        const val CALL_ANSWERED = "call-answer"
    }
}

/** Sends a push to each token. Must not block the caller. */
fun interface PushSender {
    /** A "sync now" push. */
    fun send(tokens: List<String>)

    /** A call push of [type] ([Push.CALL] or [Push.CALL_ANSWERED]). Senders in tests get it like a sync push. */
    fun sendCall(tokens: List<String>, type: String, callId: UUID) = send(tokens)
}

class PushStore(private val db: DataSource) {

    /**
     * Stores the phone's FCM token. Within a household a token moves to the newest device that registers it
     * (a reinstall that rejoined). A phone in several households has the same token in each of them.
     */
    fun setToken(deviceId: UUID, token: String?) = db.transaction { conn ->
        if (token != null) {
            conn.prepareStatement(
                """
                UPDATE device SET push_token = NULL WHERE push_token = ? AND id <> ?
                    AND household_id = (SELECT household_id FROM device WHERE id = ?)
                """.trimIndent(),
            ).run { setString(1, token); setObject(2, deviceId); setObject(3, deviceId); executeUpdate() }
        }
        conn.prepareStatement("UPDATE device SET push_token = ? WHERE id = ?").run {
            setString(1, token); setObject(2, deviceId); executeUpdate()
        }
    }

    /** FCM said the token no longer exists (the app was uninstalled). */
    fun forgetToken(token: String) = db.transaction { conn ->
        conn.prepareStatement("UPDATE device SET push_token = NULL WHERE push_token = ?").run { setString(1, token); executeUpdate() }
    }

    fun householdTokens(householdId: UUID, exceptDevice: UUID?): List<String> = tokens(
        """
        SELECT push_token FROM device WHERE household_id = ? AND status = 'approved' AND NOT revoked
            AND push_token IS NOT NULL AND id IS DISTINCT FROM ?
        """.trimIndent(),
        householdId, exceptDevice,
    )

    fun adminTokens(householdId: UUID): List<String> = tokens(
        """
        SELECT d.push_token FROM device d JOIN household h ON h.id = d.household_id
        WHERE d.household_id = ? AND d.member_id = h.admin_member_id AND d.status = 'approved' AND NOT d.revoked
            AND d.push_token IS NOT NULL
        """.trimIndent(),
        householdId,
    )

    /** A pending phone, read before it's rejected or after it's approved. */
    fun deviceTokens(householdId: UUID, deviceId: UUID): List<String> = tokens(
        "SELECT push_token FROM device WHERE household_id = ? AND id = ? AND NOT revoked AND push_token IS NOT NULL",
        householdId, deviceId,
    )

    /** A member's phones, read before the member is removed. */
    fun memberTokens(householdId: UUID, memberId: UUID): List<String> = tokens(
        "SELECT push_token FROM device WHERE household_id = ? AND member_id = ? AND NOT revoked AND push_token IS NOT NULL",
        householdId, memberId,
    )

    /** The approved phones of [members], except [exceptDevice]. */
    fun membersTokens(householdId: UUID, members: Collection<UUID>, exceptDevice: UUID?): List<String> = db.transaction { conn ->
        conn.prepareStatement(
            """
            SELECT push_token FROM device WHERE household_id = ? AND member_id = ANY(?) AND status = 'approved' AND NOT revoked
                AND push_token IS NOT NULL AND id IS DISTINCT FROM ?
            """.trimIndent(),
        ).run {
            setObject(1, householdId); setArray(2, conn.createArrayOf("uuid", members.toTypedArray())); setObject(3, exceptDevice)
            executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    private fun tokens(sql: String, vararg params: Any?): List<String> = db.transaction { conn ->
        conn.prepareStatement(sql).run {
            params.forEachIndexed { i, p -> setObject(i + 1, p) }
            executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }
}

/**
 * Sends through the FCM HTTP v1 API with a service-account key ([credentialsFile]). Sends run one at
 * a time on a background thread, after the request that caused them has been answered. Tokens are
 * never logged.
 */
class FcmSender(credentialsFile: File, private val onUnregistered: (String) -> Unit) : PushSender {
    private val log = LoggerFactory.getLogger(FcmSender::class.java)
    private val credentials = credentialsFile.inputStream().use { GoogleCredentials.fromStream(it) }
        .createScoped("https://www.googleapis.com/auth/firebase.messaging")
    private val projectId = Json.parseToJsonElement(credentialsFile.readText()).jsonObject["project_id"]!!.jsonPrimitive.content
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "fcm").apply { isDaemon = true } }

    // Data only, so the phone shows nothing itself; high priority so it wakes the phone. The collapse
    // key keeps just one pending "sync" per phone while it's offline.
    override fun send(tokens: List<String>) =
        sendAll(tokens, """"data":{"type":"sync"},"android":{"priority":"high","collapse_key":"sync","ttl":"3600s"}""")

    // No collapse key, so a pending sync can't replace a call. A ring that arrives late is no use, so it
    // expires with the ringing (30 s); an answer can wait for a phone that's offline for a while.
    override fun sendCall(tokens: List<String>, type: String, callId: UUID) {
        val ttl = if (type == Push.CALL) "30s" else "3600s"
        val data = """{"type":${Json.encodeToString(type)},"callId":"$callId"}"""
        sendAll(tokens, """"data":$data,"android":{"priority":"high","ttl":"$ttl"}""")
    }

    private fun sendAll(tokens: List<String>, fields: String) {
        if (tokens.isEmpty()) return
        executor.execute {
            try {
                credentials.refreshIfExpired()
                val accessToken = credentials.accessToken?.tokenValue ?: error("no access token")
                tokens.distinct().forEach { sendOne(it, accessToken, fields) }
            } catch (e: Exception) {
                log.warn("FCM send failed: {}", e.toString())
            }
        }
    }

    private fun sendOne(token: String, accessToken: String, fields: String) {
        val body = """{"message":{"token":${Json.encodeToString(token)},$fields}}"""
        val request = HttpRequest.newBuilder(URI("https://fcm.googleapis.com/v1/projects/$projectId/messages:send"))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        when {
            response.statusCode() in 200..299 -> Unit
            // The app was uninstalled or its token replaced: stop sending to it. Other errors (rate limits,
            // FCM being down) keep the token.
            response.statusCode() == 404 && "UNREGISTERED" in response.body() -> onUnregistered(token)
            else -> log.warn("FCM answered {}", response.statusCode())
        }
    }

    companion object {
        /** The key file named by `FCM_CREDENTIALS`, or null (no instant updates) if unset or missing. */
        fun fromEnv(store: PushStore): FcmSender? {
            val path = System.getenv("FCM_CREDENTIALS")?.takeIf { it.isNotBlank() } ?: return null
            val file = File(path)
            if (!file.isFile) {
                LoggerFactory.getLogger(FcmSender::class.java).warn("FCM_CREDENTIALS is set but {} isn't a readable file", path)
                return null
            }
            return runCatching { FcmSender(file, store::forgetToken) }
                .onFailure { LoggerFactory.getLogger(FcmSender::class.java).warn("Can't read the FCM key: {}", it.toString()) }
                .getOrNull()
        }
    }
}
