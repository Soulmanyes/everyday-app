package app.everyday.android.household

import app.everyday.android.data.ChoreCompletion
import app.everyday.android.data.ChoreCompletionEntity
import app.everyday.android.data.ChoreEntity
import app.everyday.android.data.ChoreSlotEntity
import app.everyday.android.data.ChoreSnapshot
import app.everyday.android.data.BusyEventEntity
import app.everyday.android.data.EventEntity
import app.everyday.android.data.EventMemberEntity
import app.everyday.android.data.MemberSettingsEntity
import app.everyday.android.data.ReminderEntity
import app.everyday.android.data.ReminderMode
import app.everyday.android.data.ReminderStateEntity
import app.everyday.android.data.Sharing
import app.everyday.android.data.SoundMode
import app.everyday.android.reminders.Window
import app.everyday.android.sync.SyncSnapshot
import app.everyday.android.sync.SyncedEvent
import app.everyday.android.sync.SyncedReminder
import app.everyday.android.sync.SyncedTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

data class Member(val id: String, val displayName: String, val color: String? = null)

data class HouseholdInfo(
    val id: String,
    val name: String,
    val adminMemberId: String?,
    val members: List<Member>,
    /** False while the phone waits for the admin to approve it. */
    val approved: Boolean = true,
    /** The member this phone belongs to, once approved. */
    val memberId: String? = null,
    /** Pending join requests; only sent to the admin's phone. */
    val pendingRequestIds: List<String> = emptyList(),
    /** False until the admin generates a join code (households from before 2.0). */
    val hasJoinCode: Boolean = true,
)

/** An invite for a friend to create their own household (Phase 9). */
data class InviteInfo(val id: String, val createdAt: Long, val expiresAt: Long, val usedAt: Long?)

/** A phone waiting for the admin's approval. */
data class JoinRequestInfo(val id: String, val name: String, val deviceLabel: String?)

/** [joinCode] is set for a newly created household: it's shown once. */
data class JoinResult(val token: String, val household: HouseholdInfo, val joinCode: String? = null)

/** A failed API call. [status] is 0 when the server couldn't be reached. */
class ApiError(val status: Int, override val message: String) : Exception(message) {
    val isUnauthorized get() = status == 401
}

/**
 * Minimal client for the Everyday server, using only platform classes (HttpURLConnection, org.json).
 * Secrets go in the body or headers, never the URL.
 */
class ApiClient(private val baseUrl: String, private val token: String? = null) {

    suspend fun householdExists(): Boolean = call("GET", "/status").getBoolean("householdExists")

    /** Creates a household with an invite from another household's admin (or the server's setup code). */
    suspend fun createHousehold(
        inviteCode: String,
        householdName: String,
        timezone: String,
        adminName: String,
        adminPin: String,
        deviceLabel: String,
    ): JoinResult = call(
        "POST",
        "/household",
        JSONObject()
            // The server owner can use the server's setup code in place of an invite.
            .put(if (inviteCode.trim().startsWith("NEW", ignoreCase = true)) "inviteCode" else "setupToken", inviteCode.trim())
            .put("householdName", householdName)
            .put("timezone", timezone)
            .put("adminName", adminName)
            .put("adminPin", adminPin)
            .put("deviceLabel", deviceLabel),
    ).toJoinResult()

    /** Asks to join as [name] with the household's join code. The phone stays pending until the admin approves it. */
    suspend fun join(joinCode: String, name: String, deviceLabel: String, adminPin: String? = null): JoinResult = call(
        "POST",
        "/join",
        JSONObject().put("joinCode", joinCode).put("name", name).put("deviceLabel", deviceLabel)
            .put("adminPin", adminPin ?: JSONObject.NULL),
    ).toJoinResult()

    suspend fun joinRequests(pin: String): List<JoinRequestInfo> =
        callArray("GET", "/join-requests", pin).objects().map {
            JoinRequestInfo(it.getString("id"), it.getString("name"), it.optStringOrNull("deviceLabel"))
        }

    /** Approves a request as a new member, or as the existing member [memberId]. */
    suspend fun approveRequest(pin: String, id: String, memberId: String?) {
        call("POST", "/join-requests/$id/approve", JSONObject().put("memberId", memberId ?: JSONObject.NULL), pin)
    }

    suspend fun rejectRequest(pin: String, id: String) {
        call("POST", "/join-requests/$id/reject", JSONObject(), pin)
    }

    suspend fun me(): HouseholdInfo = call("GET", "/me").toHousehold()

    // Phase 9, admin only (with the PIN): join codes, invites, deleting the household.

    /** A new join code; the old one stops working. The server shows it only this once. */
    suspend fun newJoinCode(pin: String): String = call("POST", "/household/join-code", JSONObject(), pin).getString("joinCode")

    /** The household's invites and how many it can still make. */
    suspend fun invites(pin: String): Pair<List<InviteInfo>, Int> = call("GET", "/invites", adminPin = pin).let { o ->
        o.getJSONArray("invites").objects().map {
            InviteInfo(it.getString("id"), it.getLong("createdAt"), it.getLong("expiresAt"), it.optLongOrNull("usedAt"))
        } to o.getInt("remaining")
    }

    /** A new invite code, shown only this once. */
    suspend fun createInvite(pin: String): String = call("POST", "/invites", JSONObject(), pin).getString("code")

    suspend fun cancelInvite(pin: String, id: String) {
        call("DELETE", "/invites/$id", adminPin = pin)
    }

    // Phase 9.5, admin only (with the PIN): one-time invite links into this household.

    /** The household's open member invites (unused, not cancelled, not expired). */
    suspend fun memberInvites(pin: String): List<InviteInfo> =
        call("GET", "/member-invites", adminPin = pin).getJSONArray("invites").objects().map {
            InviteInfo(it.getString("id"), it.getLong("createdAt"), it.getLong("expiresAt"), usedAt = null)
        }

    /** A new member invite code (`JOIN-...`), shown only this once. */
    suspend fun createMemberInvite(pin: String): String = call("POST", "/member-invites", JSONObject(), pin).getString("code")

    suspend fun cancelMemberInvite(pin: String, id: String) {
        call("DELETE", "/member-invites/$id", adminPin = pin)
    }

    suspend fun deleteHousehold(pin: String) {
        call("DELETE", "/household", adminPin = pin)
    }

    suspend fun leave() {
        call("DELETE", "/device")
    }

    suspend fun verifyAdminPin(pin: String) {
        call("POST", "/admin/verify", JSONObject(), pin)
    }

    suspend fun addMember(pin: String, name: String): Member =
        call("POST", "/members", JSONObject().put("displayName", name), pin).toMember()

    /** [color] null keeps the member's colour. */
    suspend fun renameMember(pin: String, id: String, name: String, color: String? = null): Member =
        call("PUT", "/members/$id", JSONObject().put("displayName", name).put("color", color ?: JSONObject.NULL), pin).toMember()

    suspend fun setMyColor(color: String): Member = call("PUT", "/me/color", JSONObject().put("color", color)).toMember()

    /** Registers this phone's FCM token for instant updates (null stops them). */
    suspend fun setPushToken(pushToken: String?) {
        call("PUT", "/me/push-token", JSONObject().put("token", pushToken ?: JSONObject.NULL))
    }

    suspend fun removeMember(pin: String, id: String) {
        call("DELETE", "/members/$id", adminPin = pin)
    }

    // Chores. Every route returns the full snapshot, so the cache is replaced from any response.

    suspend fun chores(): ChoreSnapshot = call("GET", "/chores").toChoreSnapshot()

    suspend fun saveChore(id: String, title: String, notes: String?, weekdays: Int, completion: ChoreCompletion, assigneeIds: List<String>) =
        call(
            "PUT",
            "/chores/$id",
            JSONObject()
                .put("title", title)
                .put("notes", notes ?: JSONObject.NULL)
                .put("weekdays", weekdays)
                .put("completion", completion.name)
                .put("assigneeIds", JSONArray(assigneeIds)),
        ).toChoreSnapshot()

    suspend fun deleteChore(id: String) = call("DELETE", "/chores/$id").toChoreSnapshot()

    suspend fun markChoreDone(id: String, memberId: String) =
        call("POST", "/chores/$id/done", JSONObject().put("memberId", memberId)).toChoreSnapshot()

    suspend fun undoChoreDone(id: String, memberId: String) =
        call("POST", "/chores/$id/undo", JSONObject().put("memberId", memberId)).toChoreSnapshot()

    /** One sync round trip for [memberId]: pushes the changed rows, returns everything they can see. */
    suspend fun sync(
        memberId: String,
        events: List<SyncedEvent>,
        leaves: List<EventMemberEntity>,
        reminders: List<SyncedReminder>,
        states: List<ReminderStateEntity>,
        settings: MemberSettingsEntity?,
    ): SyncSnapshot {
        val body = JSONObject()
            .put("memberId", memberId)
            .put("events", JSONArray(events.map { it.toJson() }))
            .put("leave", JSONArray(leaves.map { JSONObject().put("eventId", it.eventId).put("updatedAt", it.updatedAt) }))
            .put("reminders", JSONArray(reminders.map { it.toJson() }))
            .put("states", JSONArray(states.map { it.toJson() }))
        settings?.let {
            body.put(
                "settings",
                JSONObject().put("snoozeMinutes", it.snoozeMinutes).put("timedSound", it.timedSound.name)
                    .put("persistentSound", it.persistentSound.name).put("updatedAt", it.updatedAt),
            )
        }
        return call("POST", "/v2/sync", body).toSyncSnapshot(memberId)
    }

    private suspend fun call(method: String, path: String, body: JSONObject? = null, adminPin: String? = null): JSONObject =
        request(method, path, body, adminPin).let { if (it.isBlank()) JSONObject() else JSONObject(it) }

    private suspend fun callArray(method: String, path: String, adminPin: String? = null): JSONArray =
        JSONArray(request(method, path, null, adminPin))

    /** Returns the response body text; throws [ApiError] for errors. */
    private suspend fun request(method: String, path: String, body: JSONObject?, adminPin: String?): String =
        withContext(Dispatchers.IO) {
            val connection = try {
                URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
            } catch (e: Exception) {
                throw ApiError(0, "That server address doesn't look right.")
            }
            try {
                connection.requestMethod = method
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Accept", "application/json")
                token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                adminPin?.let { connection.setRequestProperty("X-Admin-Pin", it) }
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                val status = connection.responseCode
                val text = (if (status < 400) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (status >= 400) {
                    val message = runCatching { JSONObject(text).getString("error") }.getOrNull()
                        ?: "The server returned an error ($status)."
                    throw ApiError(status, message)
                }
                text
            } catch (e: IOException) {
                throw ApiError(0, "Couldn't reach the server. Check your internet connection.")
            } finally {
                connection.disconnect()
            }
        }

    private fun JSONObject.toJoinResult() = JoinResult(getString("token"), toHousehold(), optStringOrNull("joinCode"))

    private fun JSONObject.toHousehold(): HouseholdInfo {
        val household = getJSONObject("household")
        return HouseholdInfo(
            id = household.getString("id"),
            name = household.getString("name"),
            adminMemberId = household.optString("adminMemberId").takeIf { it.isNotEmpty() && it != "null" },
            members = getJSONArray("members").toMembers(),
            approved = optString("deviceStatus", "approved") == "approved",
            memberId = optStringOrNull("memberId"),
            pendingRequestIds = optArray("pendingRequestIds").let { a -> (0 until a.length()).map { a.getString(it) } },
            hasJoinCode = optBoolean("hasJoinCode", true),
        )
    }

    private fun JSONArray.toMembers() = (0 until length()).map { getJSONObject(it).toMember() }

    private fun JSONObject.toMember() = Member(getString("id"), getString("displayName"), optStringOrNull("color"))

    private fun JSONObject.toChoreSnapshot(): ChoreSnapshot {
        val chores = getJSONArray("chores").objects()
        val completions = getJSONArray("completions").objects()
        return ChoreSnapshot(
            today = LocalDate.parse(getString("today")),
            chores = chores.map {
                ChoreEntity(
                    id = it.getString("id"),
                    title = it.getString("title"),
                    notes = it.optString("notes").takeIf { n -> n.isNotEmpty() && !it.isNull("notes") },
                    weekdays = it.getInt("weekdays"),
                    completion = ChoreCompletion.valueOf(it.getString("completion")),
                )
            },
            assignees = chores.associate { c ->
                val ids = c.getJSONArray("assigneeIds")
                c.getString("id") to (0 until ids.length()).map { ids.getString(it) }
            },
            completions = completions.map {
                ChoreCompletionEntity(it.getString("choreId"), it.getString("memberId"), it.getString("doneOn"))
            },
            slots = optArray("slots").objects().map {
                ChoreSlotEntity(it.getString("choreId"), it.getString("memberId"), it.getInt("startMinute"), it.optLongOrNull("endMinute")?.toInt())
            },
        )
    }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    private fun SyncedEvent.toJson() = JSONObject()
        .put("id", event.id)
        .put("ownerMemberId", event.ownerMemberId)
        .put("title", event.title)
        .put("notes", event.notes ?: JSONObject.NULL)
        .put("weekdays", event.weekdays)
        .put("onDate", event.onDate ?: JSONObject.NULL)
        .put("startMinute", event.startMinute ?: JSONObject.NULL)
        // Same-day ends in endMinute, multi-day ones in endDayMinute (phones before 2.2 only read endMinute).
        .put("endMinute", (if (event.endDays == 0) event.endMinute else null) ?: JSONObject.NULL)
        .put("endDays", event.endDays)
        .put("endDayMinute", (if (event.endDays > 0) event.endMinute else null) ?: JSONObject.NULL)
        // Taking part in members (all that phones before 2.4 know), the rest in schedulingMembers (Phase 9.8).
        .put("members", JSONArray(members.filter { it.role == Sharing.MEMBER }.map { it.toJson() }))
        .put("schedulingMembers", JSONArray(members.filter { it.role == Sharing.SCHEDULING }.map { it.toJson() }))
        .put("everyone", event.everyone)
        .put("createdAt", event.createdAt)
        .put("updatedAt", event.updatedAt)
        .put("deleted", event.deleted)
        .put("showBusy", event.showBusy)

    private fun EventMemberEntity.toJson() = JSONObject().put("memberId", memberId).put("updatedAt", updatedAt)

    private fun SyncedReminder.toJson() = JSONObject()
        .put("id", reminder.id)
        .put("ownerMemberId", reminder.ownerMemberId)
        .put("eventId", reminder.eventId ?: JSONObject.NULL)
        .put("choreId", reminder.choreId ?: JSONObject.NULL)
        .put("message", reminder.message)
        .put("mode", reminder.mode.name)
        .put("weekdays", reminder.weekdays)
        .put("active", reminder.active)
        .put("times", JSONArray(times.map { JSONObject().put("minute", it.minute).put("relative", it.relative) }))
        .put("windows", JSONArray(windows.map { JSONObject().put("startMinute", it.startMinute).put("endMinute", it.endMinute) }))
        .put("leadMinutes", reminder.leadMinutes ?: JSONObject.NULL)
        .put("eachDay", reminder.eachDay)
        .put("createdAt", reminder.createdAt)
        .put("updatedAt", reminder.updatedAt)
        .put("deleted", reminder.deleted)

    private fun ReminderStateEntity.toJson() = JSONObject()
        .put("reminderId", reminderId)
        .put("doneOn", doneOn ?: JSONObject.NULL)
        .put("snoozeUntil", snoozeUntil ?: JSONObject.NULL)
        .put("updatedAt", updatedAt)

    // isNull is also true for a missing key.
    private fun JSONObject.optStringOrNull(name: String) = if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

    private fun JSONObject.optLongOrNull(name: String) = if (isNull(name)) null else getLong(name)

    private fun JSONObject.optArray(name: String) = optJSONArray(name) ?: JSONArray()

    private fun JSONObject.optIntOrNull(name: String) = if (isNull(name)) null else getInt(name)

    /** An event's end time: on its last day for a multi-day event (endMinute is then only for older apps). */
    private fun JSONObject.end() = if (optInt("endDays", 0) > 0) optIntOrNull("endDayMinute") else optIntOrNull("endMinute")

    /** An unknown sound (from a newer app on another phone) falls back to the default instead of failing sync. */
    private fun sound(name: String, default: SoundMode) = runCatching { SoundMode.valueOf(name) }.getOrDefault(default)

    private fun JSONObject.toSyncSnapshot(memberId: String): SyncSnapshot {
        val events = optArray("events").objects().map { e ->
            val id = e.getString("id")
            SyncedEvent(
                event = EventEntity(
                    id = id,
                    ownerMemberId = e.getString("ownerMemberId"),
                    title = e.getString("title"),
                    notes = e.optStringOrNull("notes"),
                    weekdays = e.optInt("weekdays", 0),
                    onDate = e.optStringOrNull("onDate"),
                    startMinute = e.optIntOrNull("startMinute"),
                    endMinute = e.end(),
                    createdAt = e.getLong("createdAt"),
                    updatedAt = e.getLong("updatedAt"),
                    deleted = e.optBoolean("deleted", false),
                    showBusy = e.optBoolean("showBusy", true),
                    endDays = e.optInt("endDays", 0),
                    everyone = e.optString("everyone", Sharing.NONE).takeIf { it in SHARING } ?: Sharing.NONE,
                ),
                members = e.optArray("members").objects().map {
                    EventMemberEntity(id, it.getString("memberId"), updatedAt = it.optLong("updatedAt", 0))
                } + e.optArray("schedulingMembers").objects().map {
                    EventMemberEntity(id, it.getString("memberId"), updatedAt = it.optLong("updatedAt", 0), role = Sharing.SCHEDULING)
                },
            )
        }
        val reminders = getJSONArray("reminders").objects().map { r ->
            SyncedReminder(
                reminder = ReminderEntity(
                    id = r.getString("id"),
                    ownerMemberId = r.getString("ownerMemberId"),
                    message = r.optString("message", ""),
                    mode = ReminderMode.valueOf(r.getString("mode")),
                    weekdays = r.optInt("weekdays", 0),
                    active = r.optBoolean("active", true),
                    choreId = r.optStringOrNull("choreId"),
                    createdAt = r.getLong("createdAt"),
                    updatedAt = r.getLong("updatedAt"),
                    deleted = r.optBoolean("deleted", false),
                    eventId = r.optStringOrNull("eventId"),
                    leadMinutes = r.optIntOrNull("leadMinutes"),
                    eachDay = r.optBoolean("eachDay", false),
                ),
                times = r.optArray("times").objects().map { SyncedTime(it.getInt("minute"), it.optBoolean("relative", false)) },
                windows = r.optArray("windows").objects().map { Window(it.getInt("startMinute"), it.getInt("endMinute")) },
            )
        }
        val states = optArray("states").objects().map {
            ReminderStateEntity(it.getString("reminderId"), memberId, it.optStringOrNull("doneOn"), it.optLongOrNull("snoozeUntil"), it.getLong("updatedAt"))
        }
        val settings = optJSONObject("settings")?.let {
            MemberSettingsEntity(
                memberId = memberId,
                snoozeMinutes = it.getInt("snoozeMinutes"),
                timedSound = sound(it.getString("timedSound"), SoundMode.SOUND_VIBRATE),
                persistentSound = sound(it.getString("persistentSound"), SoundMode.SILENT),
                updatedAt = it.getLong("updatedAt"),
            )
        }
        val rejected = optArray("rejected").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        val busy = optArray("busy").objects().map { b ->
            BusyEventEntity(
                viewerMemberId = memberId,
                id = b.getString("id"),
                ownerMemberId = b.getString("ownerMemberId"),
                weekdays = b.optInt("weekdays", 0),
                onDate = b.optStringOrNull("onDate"),
                startMinute = b.optIntOrNull("startMinute"),
                endMinute = b.end(),
                endDays = b.optInt("endDays", 0),
                title = b.optStringOrNull("title"),
            )
        }
        return SyncSnapshot(events, reminders, states, settings, rejected, busy)
    }
}

/** Sharing values this app knows; anything newer reads as not shared with everyone. */
private val SHARING = setOf(Sharing.NONE, Sharing.MEMBER, Sharing.SCHEDULING)
