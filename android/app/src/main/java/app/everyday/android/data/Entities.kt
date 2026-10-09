package app.everyday.android.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Local Room schema. IDs are client-generated UUID strings so records can be created offline
 * and synced later (Phase 4). Times of day are stored as minutes since midnight (0..1439),
 * window ends as minutes in 1..1440 (1440 = end of day).
 */

/** Phase 1 has no login, so everything belongs to this one local member until Phase 2. */
const val LOCAL_MEMBER_ID = "local"

@Entity(tableName = "member")
data class MemberEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val updatedAt: Long,
    val deleted: Boolean = false,
    /** Palette key of the member's dot colour, from the server (household-wide). */
    val color: String? = null,
)

/** SOUND is sound only (Phase 6); SOUND_VIBRATE is what SOUND used to be. */
enum class SoundMode { SOUND, VIBRATE, SOUND_VIBRATE, SILENT }

@Entity(tableName = "member_settings")
data class MemberSettingsEntity(
    @PrimaryKey val memberId: String,
    val snoozeMinutes: Int = 10,
    val timedSound: SoundMode = SoundMode.SOUND_VIBRATE,
    val persistentSound: SoundMode = SoundMode.SILENT,
    val updatedAt: Long = 0,
    /** Changed on this phone since the last sync. */
    @ColumnInfo(defaultValue = "0") val dirty: Boolean = false,
)

enum class ReminderMode { TIMED, PERSISTENT }

/**
 * Something on the timetable (Phase 6): weekly on [weekdays], or once on [onDate] (ISO date), exactly
 * one of the two. No [startMinute] means all day; [endMinute] is optional. Personal unless it has
 * [EventMemberEntity] rows; only the owner edits it.
 */
@Entity(tableName = "event", indices = [Index("ownerMemberId")])
data class EventEntity(
    @PrimaryKey val id: String,
    val ownerMemberId: String,
    val title: String,
    val notes: String? = null,
    /** Bit 0 = Monday ... bit 6 = Sunday; 0 for a one-off event. */
    val weekdays: Int = 0,
    val onDate: String? = null,
    val startMinute: Int? = null,
    val endMinute: Int? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
    /** Changed on this phone since the last sync. */
    val dirty: Boolean = false,
    /** While personal, other members see it as "Busy" (times only); false hides it from them (Phase 7). */
    @ColumnInfo(defaultValue = "1") val showBusy: Boolean = true,
)

/**
 * Another member's personal event that [viewerMemberId] sees as "Busy" (Phase 7): days and times only,
 * as the server sends them; the server never sends its title or notes. Replaced on every sync, and kept
 * apart from [EventEntity] so alarms and the week view never treat it as an event.
 */
@Entity(tableName = "busy_event", primaryKeys = ["viewerMemberId", "id"])
data class BusyEventEntity(
    val viewerMemberId: String,
    val id: String,
    val ownerMemberId: String,
    /** Bit 0 = Monday ... bit 6 = Sunday; 0 for a one-off event. */
    val weekdays: Int = 0,
    val onDate: String? = null,
    val startMinute: Int? = null,
    val endMinute: Int? = null,
)

/**
 * A member a shared event is shared with. [hidden] means this phone's member left (or declined) and it
 * hasn't synced yet; once it has, the server drops them and the event goes from this phone.
 */
@Entity(
    tableName = "event_member",
    primaryKeys = ["eventId", "memberId"],
    foreignKeys = [ForeignKey(EventEntity::class, ["id"], ["eventId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("memberId")],
)
data class EventMemberEntity(
    val eventId: String,
    val memberId: String,
    val hidden: Boolean = false,
    val updatedAt: Long = 0,
    /** This member left the event on this phone since the last sync. */
    val dirty: Boolean = false,
)

/**
 * A reminder on an event ([eventId]) or a chore ([choreId]). Event reminders take their title and days
 * from the event: TIMED ones have times (fixed, or relative to the start), PERSISTENT ones show all day
 * ([leadMinutes] null) or from [leadMinutes] before the start until the end. Chore reminders keep their
 * own [message], [weekdays], times and windows.
 */
@Entity(tableName = "reminder", indices = [Index("ownerMemberId"), Index("eventId")])
data class ReminderEntity(
    @PrimaryKey val id: String,
    val ownerMemberId: String,
    val message: String,
    val mode: ReminderMode,
    /** Bit 0 = Monday ... bit 6 = Sunday. */
    val weekdays: Int,
    val active: Boolean = true,
    val choreId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
    /** Unused since Room 6 (Phase 6); SQLite on Android 9 can't drop a column. */
    @ColumnInfo(defaultValue = "1") val ownerReceives: Boolean = true,
    /** Changed on this phone since the last sync. */
    @ColumnInfo(defaultValue = "0") val dirty: Boolean = false,
    val eventId: String? = null,
    val leadMinutes: Int? = null,
)

@Entity(
    tableName = "reminder_time",
    foreignKeys = [ForeignKey(ReminderEntity::class, ["id"], ["reminderId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("reminderId")],
)
data class ReminderTimeEntity(
    @PrimaryKey val id: String,
    val reminderId: String,
    /** A minute of the day, or with [relative] the minutes before the event starts. */
    val minuteOfDay: Int,
    @ColumnInfo(defaultValue = "0") val relative: Boolean = false,
)

@Entity(
    tableName = "reminder_window",
    foreignKeys = [ForeignKey(ReminderEntity::class, ["id"], ["reminderId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("reminderId")],
)
data class ReminderWindowEntity(
    @PrimaryKey val id: String,
    val reminderId: String,
    val startMinute: Int,
    val endMinute: Int,
)

/** Done/snooze state of a reminder for one member. Kept in the database so it survives reboots. */
@Entity(
    tableName = "reminder_state",
    primaryKeys = ["reminderId", "memberId"],
    foreignKeys = [ForeignKey(ReminderEntity::class, ["id"], ["reminderId"], onDelete = ForeignKey.CASCADE)],
)
data class ReminderStateEntity(
    val reminderId: String,
    val memberId: String,
    /** ISO date (yyyy-MM-dd) the persistent reminder was marked done, hiding it for that day. */
    val doneOn: String? = null,
    /** Epoch millis when a snoozed timed reminder fires again. */
    val snoozeUntil: Long? = null,
    val updatedAt: Long = 0,
    /** Changed on this phone since the last sync. */
    val dirty: Boolean = false,
)
