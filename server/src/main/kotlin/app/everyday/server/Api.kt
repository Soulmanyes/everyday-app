package app.everyday.server

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

@Serializable
data class StatusResponse(val householdExists: Boolean)

@Serializable
data class CreateHouseholdRequest(
    val setupToken: String,
    val householdName: String,
    val householdCode: String,
    val timezone: String,
    val adminName: String,
    val adminPin: String,
    val deviceLabel: String? = null,
)

@Serializable
data class JoinRequest(val householdCode: String, val deviceLabel: String? = null)

@Serializable
data class HouseholdDto(val id: String, val name: String, val timezone: String, val adminMemberId: String?)

@Serializable
data class MemberDto(val id: String, val displayName: String, val color: String? = null)

/** Returned when a phone creates or joins the household. The token is shown only once. */
@Serializable
data class SessionResponse(val token: String, val household: HouseholdDto, val members: List<MemberDto>)

@Serializable
data class HouseholdResponse(val household: HouseholdDto, val members: List<MemberDto>)

@Serializable
data class MemberRequest(val displayName: String, val color: String? = null)

@Serializable
data class ErrorResponse(val error: String)

/** Thrown by route code; turned into a JSON error response by StatusPages. */
class ApiException(val status: HttpStatusCode, override val message: String) : RuntimeException(message)

fun badRequest(message: String): Nothing = throw ApiException(HttpStatusCode.BadRequest, message)
