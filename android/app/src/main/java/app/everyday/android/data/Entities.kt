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

enum class SoundMode { SOUND, VIBRATE, SILENT }

@Entity(tableName = "member_settings")
data class MemberSettingsEntity(
    @PrimaryKey val memberId: String,
    val snoozeMinutes: Int = 10,
    val timedSound: SoundMode = SoundMode.SOUND,
    val persistentSound: SoundMode = SoundMode.SILENT,
    val updatedAt: Long = 0,
    /** Changed on this phone since the last sync. */
    @ColumnInfo(defaultValue = "0") val dirty: Boolean = false,
)

enum class ReminderMode { TIMED, PERSISTENT }

@Entity(tableName = "reminder", indices = [Index("ownerMemberId")])
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
    /** "Remind me too": whether the owner of a shared reminder gets it as well. */
    @ColumnInfo(defaultValue = "1") val ownerReceives: Boolean = true,
    /** Changed on this phone since the last sync. */
    @ColumnInfo(defaultValue = "0") val dirty: Boolean = false,
)

@Entity(
    tableName = "reminder_time",
    foreignKeys = [ForeignKey(ReminderEntity::class, ["id"], ["reminderId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("reminderId")],
)
data class ReminderTimeEntity(
    @PrimaryKey val id: String,
    val reminderId: String,
    val minuteOfDay: Int,
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

enum class RecipientStatus { PENDING, ACCEPTED, DECLINED }

/** A member invited to a shared reminder, with their answer. */
@Entity(
    tableName = "reminder_recipient",
    primaryKeys = ["reminderId", "memberId"],
    foreignKeys = [ForeignKey(ReminderEntity::class, ["id"], ["reminderId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("memberId")],
)
data class ReminderRecipientEntity(
    val reminderId: String,
    val memberId: String,
    val status: RecipientStatus = RecipientStatus.PENDING,
    val updatedAt: Long = 0,
    /** This member changed their own answer on this phone since the last sync. */
    val dirty: Boolean = false,
)
