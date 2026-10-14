package app.everyday.server

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

@Serializable
data class StatusResponse(val householdExists: Boolean)

/**
 * Creates a household with an [inviteCode] from another household's admin, or with the server's
 * [setupToken] (the owner's own way in). The server generates the join code. Apps before 2.0 send
 * [householdCode] (a code the admin chose) and are told to update.
 */
@Serializable
data class CreateHouseholdRequest(
    val householdName: String,
    val timezone: String,
    val adminName: String,
    val adminPin: String,
    val deviceLabel: String? = null,
    val inviteCode: String? = null,
    val setupToken: String? = null,
    val householdCode: String? = null,
)

/** Joining with a household's [joinCode]. Apps before 2.0 send only [householdCode] and are told to update. */
@Serializable
data class JoinRequest(
    val joinCode: String? = null,
    val name: String = "",
    val deviceLabel: String? = null,
    /** The admin joining from a new phone: with the right PIN the phone is approved as the admin at once. */
    val adminPin: String? = null,
    val householdCode: String? = null,
)

/** A household's new join code, shown once to the admin (the server keeps only a hash of its secret part). */
@Serializable
data class JoinCodeResponse(val joinCode: String)

/** An invite to create a household. The code itself is shown only when it's made ([CreatedInviteResponse]). */
@Serializable
data class InviteDto(val id: String, val createdAt: Long, val expiresAt: Long, val usedAt: Long? = null)

@Serializable
data class CreatedInviteResponse(val invite: InviteDto, val code: String)

@Serializable
data class InvitesResponse(val invites: List<InviteDto>, val remaining: Int)

/** Open member invites (Phase 9.5); the codes themselves are shown only when made. */
@Serializable
data class MemberInvitesResponse(val invites: List<InviteDto>)

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
    /** The new household's join code, shown once to its creator (Phase 9). */
    val joinCode: String? = null,
)

@Serializable
data class HouseholdResponse(
    val household: HouseholdDto,
    val members: List<MemberDto>,
    val deviceStatus: String = "approved",
    val memberId: String? = null,
    /** Pending join request ids; only sent to the admin's phone, so it can notify. */
    val pendingRequestIds: List<String> = emptyList(),
    /** False until the admin generates a join code (households from before 2.0): nobody new can join. */
    val hasJoinCode: Boolean = true,
    /** Epoch millis of the last time wrong admin PINs locked anything; only sent to the admin's phone, so it can warn. */
    val pinLockedAt: Long? = null,
)

/** A phone waiting for the admin's approval. */
@Serializable
data class JoinRequestDto(val id: String, val name: String, val deviceLabel: String? = null, val createdAt: Long)

/** Approve as a new member named as requested ([memberId] null), or link the phone to an existing member. */
@Serializable
data class ApproveRequest(val memberId: String? = null)

/** [color] null keeps the member's current colour. */
@Serializable
data class MemberRequest(val displayName: String, val color: String? = null)

@Serializable
data class ColorRequest(val color: String)

/** The phone's FCM token for instant updates; null stops pushes to this phone. */
@Serializable
data class PushTokenRequest(val token: String? = null)

/** Palette keys, in the order free colours are handed out. The app maps each key to a colour. */
val MEMBER_COLORS = listOf("blue", "red", "purple", "orange", "yellow", "pink", "cyan", "brown", "indigo", "grey", "maroon", "sky")

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
    /** Only the creator sees it (Phase 9.9). Null from phones before 2.5: the server keeps the stored value. */
    val personal: Boolean? = null,
)

@Serializable
data class ChoreDto(
    val id: String,
    val title: String,
    val notes: String? = null,
    val weekdays: Int,
    val completion: ChoreCompletion,
    val assigneeIds: List<String>,
    /** The member who created it; null for chores from before Phase 9.9. */
    val createdBy: String? = null,
    val personal: Boolean = false,
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

// Sync (Phase 4, events in Phase 6). Times are epoch millis from the phone's clock; last write wins on updatedAt.

@Serializable
data class WindowDto(val startMinute: Int, val endMinute: Int)

/** A member an event is shared with, and when the owner added them (phone clock). */
@Serializable
data class EventMemberDto(val memberId: String, val updatedAt: Long = 0)

/** How an event is shared with the whole household ([EventSyncDto.everyone], Phase 9.8). */
object Everyone {
    const val NONE = "NONE"
    /** Everyone takes part, as if each member were in [EventSyncDto.members]. */
    const val MEMBER = "MEMBER"
    /** Everyone sees it for scheduling, as if in [EventSyncDto.schedulingMembers]. */
    const val SCHEDULING = "SCHEDULING"
    val ALL = setOf(NONE, MEMBER, SCHEDULING)
}

/**
 * Something on the timetable: weekly on [weekdays], or once on [onDate] (ISO date), exactly one of the two.
 * No [startMinute] means all day; [endMinute] is optional.
 *
 * Multi-day events (Phase 9.6) end [endDays] after the day they start, at [endDayMinute] on that day (null
 * for an all-day event). For phones before 2.2, which only know [endMinute], a multi-day event reads as
 * lasting from its start until midnight of its first day: the server sends [endMinute] 1440 (null when all
 * day). Those phones don't send [endDays], and the server then keeps the stored end.
 */
@Serializable
data class EventSyncDto(
    val id: String,
    val ownerMemberId: String,
    val title: String,
    val notes: String? = null,
    val weekdays: Int = 0,
    val onDate: String? = null,
    val startMinute: Int? = null,
    val endMinute: Int? = null,
    val members: List<EventMemberDto> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
    /**
     * Whether other members see this personal event as "Busy" (Phase 7). Null from phones before 1.4.0,
     * which keeps the stored value (Busy for a new event).
     */
    val showBusy: Boolean? = null,
    /** Days after the start day the event ends (0 = the same day). Null from phones before 2.2. */
    val endDays: Int? = null,
    /** With [endDays] > 0: the end on the last day. */
    val endDayMinute: Int? = null,
    /**
     * Members who see it for scheduling only (Phase 9.8): its title and times, as a [BusyDto] with a title.
     * [members] are those taking part. Null from phones before 2.4, which keeps the stored list.
     */
    val schedulingMembers: List<EventMemberDto>? = null,
    /** Shared with the whole household ([Everyone]). Null from phones before 2.4, which keeps the stored value. */
    val everyone: String? = null,
)

/**
 * Another member's event shown as "Busy": its days and times only, never its notes. It has a [title] only
 * when it's shared with the viewer for scheduling (Phase 9.8); phones before 2.4 show those as plain Busy.
 */
@Serializable
data class BusyDto(
    val id: String,
    val ownerMemberId: String,
    val weekdays: Int = 0,
    val onDate: String? = null,
    val startMinute: Int? = null,
    val endMinute: Int? = null,
    /** As in [EventSyncDto]. */
    val endDays: Int = 0,
    val endDayMinute: Int? = null,
    val title: String? = null,
)

/** A reminder time: a minute of the day, or with [relative] the minutes before the event starts. */
@Serializable
data class TimeDto(val minute: Int, val relative: Boolean = false)

/**
 * A reminder on an event ([eventId]) or a chore ([choreId]). Event reminders take their days and title from
 * the event: TIMED ones have [times], PERSISTENT ones run all day ([leadMinutes] null) or from [leadMinutes]
 * before the start until the end. Chore reminders keep their own [message], [weekdays], times and [windows].
 */
@Serializable
data class ReminderSyncDto(
    val id: String,
    val ownerMemberId: String,
    val eventId: String? = null,
    val choreId: String? = null,
    val message: String = "",
    /** TIMED or PERSISTENT. */
    val mode: String,
    val weekdays: Int = 0,
    val active: Boolean = true,
    val times: List<TimeDto> = emptyList(),
    val windows: List<WindowDto> = emptyList(),
    val leadMinutes: Int? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
    /**
     * A persistent reminder on a multi-day event (Phase 9.6): all day on each of its days (true), or over the
     * whole event (false). Null from phones before 2.2, which keeps the stored value.
     */
    val eachDay: Boolean? = null,
)

/** Done/snooze state of one reminder for the syncing member. [doneOn] is an ISO date. */
@Serializable
data class StateSyncDto(val reminderId: String, val doneOn: String? = null, val snoozeUntil: Long? = null, val updatedAt: Long)

@Serializable
data class SettingsSyncDto(val snoozeMinutes: Int, val timedSound: String, val persistentSound: String, val updatedAt: Long)

/** A member leaving (or declining) an event shared with them, or hiding one shared for scheduling. */
@Serializable
data class LeaveSyncDto(val eventId: String, val updatedAt: Long)

@Serializable
data class SyncRequest(
    val memberId: String,
    val events: List<EventSyncDto> = emptyList(),
    val reminders: List<ReminderSyncDto> = emptyList(),
    val states: List<StateSyncDto> = emptyList(),
    val settings: SettingsSyncDto? = null,
    val leave: List<LeaveSyncDto> = emptyList(),
)

/**
 * Everything the member can see after the push: events they own or that are shared with them, and their
 * own reminders (both with tombstones), state rows and settings. [rejected] lists pushed ids the server refused.
 */
@Serializable
data class SyncResponse(
    val events: List<EventSyncDto>,
    val reminders: List<ReminderSyncDto>,
    val states: List<StateSyncDto>,
    val settings: SettingsSyncDto?,
    val rejected: List<String>,
    /**
     * Other members' events shown as Busy (Phase 7), and those shared with me for scheduling, with their title
     * (Phase 9.8). Phones before 1.4.0 ignore it.
     */
    val busy: List<BusyDto> = emptyList(),
)
