package app.everyday.android.household

import app.everyday.android.data.ChoreCompletion
import app.everyday.android.data.ChoreCompletionEntity
import app.everyday.android.data.ChoreEntity
import app.everyday.android.data.ChoreSnapshot
import app.everyday.android.data.MemberSettingsEntity
import app.everyday.android.data.ReminderEntity
import app.everyday.android.data.ReminderMode
import app.everyday.android.data.ReminderRecipientEntity
import app.everyday.android.data.ReminderStateEntity
import app.everyday.android.data.SoundMode
import app.everyday.android.reminders.Window
import app.everyday.android.sync.SyncSnapshot
import app.everyday.android.sync.SyncedReminder
import app.everyday.android.sync.recipientStatus
import app.everyday.android.sync.wire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

data class Member(val id: String, val displayName: String)

data class HouseholdInfo(val name: String, val adminMemberId: String?, val members: List<Member>)

data class JoinResult(val token: String, val household: HouseholdInfo)

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

    suspend fun createHousehold(
        setupToken: String,
        householdName: String,
        householdCode: String,
        timezone: String,
        adminName: String,
        adminPin: String,
        deviceLabel: String,
    ): JoinResult = call(
        "POST",
        "/household",
        JSONObject()
            .put("setupToken", setupToken)
            .put("householdName", householdName)
            .put("householdCode", householdCode)
            .put("timezone", timezone)
            .put("adminName", adminName)
            .put("adminPin", adminPin)
            .put("deviceLabel", deviceLabel),
    ).toJoinResult()

    suspend fun join(householdCode: String, deviceLabel: String): JoinResult =
        call("POST", "/join", JSONObject().put("householdCode", householdCode).put("deviceLabel", deviceLabel)).toJoinResult()

    suspend fun me(): HouseholdInfo = call("GET", "/me").toHousehold()

    suspend fun leave() {
        call("DELETE", "/device")
    }

    suspend fun verifyAdminPin(pin: String) {
        call("POST", "/admin/verify", JSONObject(), pin)
    }

    suspend fun addMember(pin: String, name: String): Member =
        call("POST", "/members", JSONObject().put("displayName", name), pin).toMember()

    suspend fun renameMember(pin: String, id: String, name: String): Member =
        call("PUT", "/members/$id", JSONObject().put("displayName", name), pin).toMember()

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
        reminders: List<SyncedReminder>,
        states: List<ReminderStateEntity>,
        settings: MemberSettingsEntity?,
        answers: List<ReminderRecipientEntity>,
    ): SyncSnapshot {
        val body = JSONObject()
            .put("memberId", memberId)
            .put("reminders", JSONArray(reminders.map { it.toJson() }))
            .put("states", JSONArray(states.map { it.toJson() }))
            .put("recipientStatuses", JSONArray(answers.map {
                JSONObject().put("reminderId", it.reminderId).put("status", it.status.wire()).put("updatedAt", it.updatedAt)
            }))
        settings?.let {
            body.put(
                "settings",
                JSONObject().put("snoozeMinutes", it.snoozeMinutes).put("timedSound", it.timedSound.name)
                    .put("persistentSound", it.persistentSound.name).put("updatedAt", it.updatedAt),
            )
        }
        return call("POST", "/sync", body).toSyncSnapshot(memberId)
    }

    private suspend fun call(method: String, path: String, body: JSONObject? = null, adminPin: String? = null): JSONObject =
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
                if (text.isBlank()) JSONObject() else JSONObject(text)
            } catch (e: IOException) {
                throw ApiError(0, "Couldn't reach the server. Check your internet connection.")
            } finally {
                connection.disconnect()
            }
        }

    private fun JSONObject.toJoinResult() = JoinResult(getString("token"), toHousehold())

    private fun JSONObject.toHousehold(): HouseholdInfo {
        val household = getJSONObject("household")
        return HouseholdInfo(
            name = household.getString("name"),
            adminMemberId = household.optString("adminMemberId").takeIf { it.isNotEmpty() && it != "null" },
            members = getJSONArray("members").toMembers(),
        )
    }

    private fun JSONArray.toMembers() = (0 until length()).map { getJSONObject(it).toMember() }

    private fun JSONObject.toMember() = Member(getString("id"), getString("displayName"))

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
        )
    }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    private fun SyncedReminder.toJson() = JSONObject()
        .put("id", reminder.id)
        .put("ownerMemberId", reminder.ownerMemberId)
        .put("choreId", reminder.choreId ?: JSONObject.NULL)
        .put("message", reminder.message)
        .put("mode", reminder.mode.name)
        .put("weekdays", reminder.weekdays)
        .put("active", reminder.active)
        .put("ownerReceives", reminder.ownerReceives)
        .put("times", JSONArray(times))
        .put("windows", JSONArray(windows.map { JSONObject().put("startMinute", it.startMinute).put("endMinute", it.endMinute) }))
        .put("recipients", JSONArray(recipients.map { JSONObject().put("memberId", it.memberId) }))
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

    private fun JSONObject.toSyncSnapshot(memberId: String): SyncSnapshot {
        val reminders = getJSONArray("reminders").objects().map { r ->
            val id = r.getString("id")
            SyncedReminder(
                reminder = ReminderEntity(
                    id = id,
                    ownerMemberId = r.getString("ownerMemberId"),
                    message = r.getString("message"),
                    mode = ReminderMode.valueOf(r.getString("mode")),
                    weekdays = r.getInt("weekdays"),
                    active = r.optBoolean("active", true),
                    choreId = r.optStringOrNull("choreId"),
                    createdAt = r.getLong("createdAt"),
                    updatedAt = r.getLong("updatedAt"),
                    deleted = r.optBoolean("deleted", false),
                    ownerReceives = r.optBoolean("ownerReceives", true),
                ),
                times = r.optArray("times").let { a -> (0 until a.length()).map { a.getInt(it) } },
                windows = r.optArray("windows").objects().map { Window(it.getInt("startMinute"), it.getInt("endMinute")) },
                recipients = r.optArray("recipients").objects().map {
                    ReminderRecipientEntity(id, it.getString("memberId"), recipientStatus(it.optString("status", "pending")), it.optLong("updatedAt", 0))
                },
            )
        }
        val states = optArray("states").objects().map {
            ReminderStateEntity(it.getString("reminderId"), memberId, it.optStringOrNull("doneOn"), it.optLongOrNull("snoozeUntil"), it.getLong("updatedAt"))
        }
        val settings = optJSONObject("settings")?.let {
            MemberSettingsEntity(
                memberId = memberId,
                snoozeMinutes = it.getInt("snoozeMinutes"),
                timedSound = SoundMode.valueOf(it.getString("timedSound")),
                persistentSound = SoundMode.valueOf(it.getString("persistentSound")),
                updatedAt = it.getLong("updatedAt"),
            )
        }
        val rejected = optArray("rejected").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        return SyncSnapshot(reminders, states, settings, rejected)
    }
}
