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
data class JoinRequest(val householdCode: String, val name: String = "", val deviceLabel: String? = null)

@Serializable
data class HouseholdDto(val id: String, val name: String, val timezone: String, val adminMemberId: String?)

@Serializable
data class MemberDto(val id: String, val displayName: String, val color: String? = null)

/**
 * Returned when a phone creates or joins the household. The token is shown only once. A joining
 * phone is `pending` until the admin approves it; then [memberId] is the member it belongs to.
 */
@Serializable
data class SessionResponse(
    val token: String,
    val household: HouseholdDto,
    val members: List<MemberDto>,
    val deviceStatus: String = "approved",
    val memberId: String? = null,
)

@Serializable
data class HouseholdResponse(
    val household: HouseholdDto,
    val members: List<MemberDto>,
    val deviceStatus: String = "approved",
    val memberId: String? = null,
    /** Pending join request ids; only sent to the admin's phone, so it can notify. */
    val pendingRequestIds: List<String> = emptyList(),
)

/** A phone waiting for the admin's approval. */
@Serializable
data class JoinRequestDto(val id: String, val name: String, val deviceLabel: String? = null, val createdAt: Long)

/** Approve as a new member named as requested ([memberId] null), or link the phone to an existing member. */
@Serializable
data class ApproveRequest(val memberId: String? = null)

@Serializable
data class MemberRequest(val displayName: String, val color: String? = null)

@Serializable
data class ErrorResponse(val error: String)

/** Thrown by route code; turned into a JSON error response by StatusPages. */
class ApiException(val status: HttpStatusCode, override val message: String) : RuntimeException(message)

fun badRequest(message: String): Nothing = throw ApiException(HttpStatusCode.BadRequest, message)

/** ANY: done for the day once anyone marks it. EACH: every assignee does their own part. */
enum class ChoreCompletion { ANY, EACH }

@Serializable
data class ChoreRequest(
    val title: String,
    val notes: String? = null,
    /** Bit 0 = Monday ... bit 6 = Sunday. */
    val weekdays: Int,
    val completion: ChoreCompletion,
    val assigneeIds: List<String>,
)

@Serializable
data class ChoreDto(
    val id: String,
    val title: String,
    val notes: String? = null,
    val weekdays: Int,
    val completion: ChoreCompletion,
    val assigneeIds: List<String>,
)

/** A member did a chore on a day (household timezone, ISO date). */
@Serializable
data class ChoreCompletionDto(val choreId: String, val memberId: String, val doneOn: String)

@Serializable
data class ChoreDoneRequest(val memberId: String)

/**
 * Every chore with its assignees, plus today's completions. Returned by every chore route,
 * so the phone replaces its cache from any response.
 */
@Serializable
data class ChoresResponse(
    val today: String,
    val chores: List<ChoreDto>,
    val completions: List<ChoreCompletionDto>,
    /** When each assignee is reminded about each chore, from their chore reminders, for the timetable. */
    val slots: List<ChoreSlotDto> = emptyList(),
)

/** One reminder slot of an assignee for a chore: a time ([endMinute] null) or a window. */
@Serializable
data class ChoreSlotDto(val choreId: String, val memberId: String, val startMinute: Int, val endMinute: Int? = null)

// Sync (Phase 4). Times are epoch millis from the phone's clock; last write wins on updatedAt.

@Serializable
data class WindowDto(val startMinute: Int, val endMinute: Int)

/** A recipient of a shared reminder. In a push from the owner only [memberId] is read. */
@Serializable
data class RecipientDto(val memberId: String, val status: String = "pending", val updatedAt: Long = 0)

@Serializable
data class ReminderSyncDto(
    val id: String,
    val ownerMemberId: String,
    val choreId: String? = null,
    val message: String,
    /** TIMED or PERSISTENT. */
    val mode: String,
    val weekdays: Int,
    val active: Boolean = true,
    val ownerReceives: Boolean = true,
    val times: List<Int> = emptyList(),
    val windows: List<WindowDto> = emptyList(),
    val recipients: List<RecipientDto> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

/** Done/snooze state of one reminder for the syncing member. [doneOn] is an ISO date. */
@Serializable
data class StateSyncDto(val reminderId: String, val doneOn: String? = null, val snoozeUntil: Long? = null, val updatedAt: Long)

@Serializable
data class SettingsSyncDto(val snoozeMinutes: Int, val timedSound: String, val persistentSound: String, val updatedAt: Long)

/** An invitee opting in (accepted) or out (declined) of a shared reminder. */
@Serializable
data class RecipientStatusDto(val reminderId: String, val status: String, val updatedAt: Long)

@Serializable
data class SyncRequest(
    val memberId: String,
    val reminders: List<ReminderSyncDto> = emptyList(),
    val states: List<StateSyncDto> = emptyList(),
    val settings: SettingsSyncDto? = null,
    val recipientStatuses: List<RecipientStatusDto> = emptyList(),
)

/**
 * Everything the member can see after the push: reminders they own or were invited to (with
 * tombstones), their state rows and settings. [rejected] lists pushed ids the server refused.
 */
@Serializable
data class SyncResponse(
    val reminders: List<ReminderSyncDto>,
    val states: List<StateSyncDto>,
    val settings: SettingsSyncDto?,
    val rejected: List<String>,
)
