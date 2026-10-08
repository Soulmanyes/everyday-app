package app.everyday.android.household

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

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
}
