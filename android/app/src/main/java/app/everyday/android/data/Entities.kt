package app.everyday.android.data

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
)

enum class SoundMode { SOUND, VIBRATE, SILENT }

@Entity(tableName = "member_settings")
data class MemberSettingsEntity(
    @PrimaryKey val memberId: String,
    val snoozeMinutes: Int = 10,
    val timedSound: SoundMode = SoundMode.SOUND,
    val persistentSound: SoundMode = SoundMode.SILENT,
    val updatedAt: Long = 0,
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

/** Per-reminder runtime state. Kept in the database so it survives reboots. */
@Entity(
    tableName = "reminder_state",
    foreignKeys = [ForeignKey(ReminderEntity::class, ["id"], ["reminderId"], onDelete = ForeignKey.CASCADE)],
)
data class ReminderStateEntity(
    @PrimaryKey val reminderId: String,
    /** ISO date (yyyy-MM-dd) the persistent reminder was marked done, hiding it for that day. */
    val doneOn: String? = null,
    /** Epoch millis when a snoozed timed reminder fires again. */
    val snoozeUntil: Long? = null,
    val updatedAt: Long = 0,
)
