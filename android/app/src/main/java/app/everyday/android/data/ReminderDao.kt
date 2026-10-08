package app.everyday.android.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class ReminderWithDetails(
    @Embedded val reminder: ReminderEntity,
    @Relation(parentColumn = "id", entityColumn = "reminderId")
    val times: List<ReminderTimeEntity>,
    @Relation(parentColumn = "id", entityColumn = "reminderId")
    val windows: List<ReminderWindowEntity>,
    @Relation(parentColumn = "id", entityColumn = "reminderId")
    val state: ReminderStateEntity?,
)

@Dao
interface ReminderDao {
    @Transaction
    @Query("SELECT * FROM reminder WHERE ownerMemberId = :memberId AND deleted = 0")
    fun observeReminders(memberId: String): Flow<List<ReminderWithDetails>>

    @Transaction
    @Query("SELECT * FROM reminder WHERE ownerMemberId = :memberId AND deleted = 0")
    suspend fun getReminders(memberId: String): List<ReminderWithDetails>

    @Transaction
    @Query("SELECT * FROM reminder WHERE id = :id")
    suspend fun getReminder(id: String): ReminderWithDetails?

    @Upsert
    suspend fun upsertReminder(reminder: ReminderEntity)

    @Query("DELETE FROM reminder_time WHERE reminderId = :reminderId")
    suspend fun deleteTimes(reminderId: String)

    @Query("DELETE FROM reminder_window WHERE reminderId = :reminderId")
    suspend fun deleteWindows(reminderId: String)

    @Insert
    suspend fun insertTimes(times: List<ReminderTimeEntity>)

    @Insert
    suspend fun insertWindows(windows: List<ReminderWindowEntity>)

    /** Replaces a reminder together with its times and windows. */
    @Transaction
    suspend fun saveReminder(
        reminder: ReminderEntity,
        times: List<ReminderTimeEntity>,
        windows: List<ReminderWindowEntity>,
    ) {
        upsertReminder(reminder)
        deleteTimes(reminder.id)
        deleteWindows(reminder.id)
        insertTimes(times)
        insertWindows(windows)
    }

    @Query("UPDATE reminder SET deleted = 1, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE reminder SET active = :active, updatedAt = :now WHERE id = :id")
    suspend fun setActive(id: String, active: Boolean, now: Long)

    @Query("SELECT * FROM reminder_state WHERE reminderId = :id")
    suspend fun getState(id: String): ReminderStateEntity?

    @Upsert
    suspend fun upsertState(state: ReminderStateEntity)

    @Query("UPDATE reminder SET ownerMemberId = :to, updatedAt = :now WHERE ownerMemberId = :from")
    suspend fun moveReminders(from: String, to: String, now: Long)
}

@Dao
interface MemberDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(member: MemberEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSettingsIfMissing(settings: MemberSettingsEntity)

    @Query("SELECT * FROM member_settings WHERE memberId = :memberId")
    fun observeSettings(memberId: String): Flow<MemberSettingsEntity?>

    @Query("SELECT * FROM member_settings WHERE memberId = :memberId")
    suspend fun getSettings(memberId: String): MemberSettingsEntity?

    @Upsert
    suspend fun upsertSettings(settings: MemberSettingsEntity)

    @Query("SELECT * FROM member WHERE deleted = 0 AND id != :excludeId ORDER BY displayName COLLATE NOCASE")
    fun observeMembers(excludeId: String = LOCAL_MEMBER_ID): Flow<List<MemberEntity>>

    @Upsert
    suspend fun upsertMembers(members: List<MemberEntity>)

    /** Marks cached members that the server no longer lists as deleted. */
    @Query("UPDATE member SET deleted = 1 WHERE id NOT IN (:keepIds) AND id != :localId")
    suspend fun markOthersDeleted(keepIds: List<String>, localId: String = LOCAL_MEMBER_ID)

    @Query("DELETE FROM member_settings WHERE memberId = :memberId")
    suspend fun deleteSettings(memberId: String)

    @Query("DELETE FROM member WHERE id = :memberId")
    suspend fun deleteMember(memberId: String)
}
