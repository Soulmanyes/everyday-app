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

private const val VISIBLE = """
    SELECT * FROM reminder WHERE deleted = 0 AND (ownerMemberId = :memberId
    OR id IN (SELECT reminderId FROM reminder_recipient WHERE memberId = :memberId AND status = 'ACCEPTED'))
"""

data class ReminderWithDetails(
    @Embedded val reminder: ReminderEntity,
    @Relation(parentColumn = "id", entityColumn = "reminderId")
    val times: List<ReminderTimeEntity>,
    @Relation(parentColumn = "id", entityColumn = "reminderId")
    val windows: List<ReminderWindowEntity>,
    /** Done/snooze state, one row per member (shared reminders have several). */
    @Relation(parentColumn = "id", entityColumn = "reminderId")
    val states: List<ReminderStateEntity>,
    @Relation(parentColumn = "id", entityColumn = "reminderId")
    val recipients: List<ReminderRecipientEntity>,
)

@Dao
interface ReminderDao {
    /** Reminders [memberId] owns, plus shared reminders they accepted. */
    @Transaction
    @Query(VISIBLE)
    fun observeVisible(memberId: String): Flow<List<ReminderWithDetails>>

    @Transaction
    @Query(VISIBLE)
    suspend fun getVisible(memberId: String): List<ReminderWithDetails>

    /** Other members' reminders [memberId] is invited to, whatever their answer. */
    @Transaction
    @Query(
        """
        SELECT * FROM reminder WHERE deleted = 0 AND ownerMemberId != :memberId
        AND id IN (SELECT reminderId FROM reminder_recipient WHERE memberId = :memberId)
        """,
    )
    fun observeSharedWith(memberId: String): Flow<List<ReminderWithDetails>>

    /** Everything sync compares for [memberId]: owned rows (with tombstones) and every reminder they're invited to. */
    @Transaction
    @Query(
        """
        SELECT * FROM reminder WHERE ownerMemberId = :memberId
        OR id IN (SELECT reminderId FROM reminder_recipient WHERE memberId = :memberId)
        """,
    )
    suspend fun getForSync(memberId: String): List<ReminderWithDetails>

    @Transaction
    @Query("SELECT * FROM reminder WHERE ownerMemberId = :memberId AND dirty = 1")
    suspend fun getDirtyOwned(memberId: String): List<ReminderWithDetails>

    @Query("SELECT * FROM reminder_state WHERE memberId = :memberId AND dirty = 1")
    suspend fun getDirtyStates(memberId: String): List<ReminderStateEntity>

    @Query("SELECT * FROM reminder_state WHERE memberId = :memberId")
    suspend fun getStates(memberId: String): List<ReminderStateEntity>

    /** [memberId]'s own answers to invites, changed on this phone. */
    @Query("SELECT * FROM reminder_recipient WHERE memberId = :memberId AND dirty = 1")
    suspend fun getDirtyAnswers(memberId: String): List<ReminderRecipientEntity>

    @Transaction
    @Query("SELECT * FROM reminder WHERE id = :id")
    suspend fun getReminder(id: String): ReminderWithDetails?

    @Upsert
    suspend fun upsertReminder(reminder: ReminderEntity)

    @Query("DELETE FROM reminder_time WHERE reminderId = :reminderId")
    suspend fun deleteTimes(reminderId: String)

    @Query("DELETE FROM reminder_window WHERE reminderId = :reminderId")
    suspend fun deleteWindows(reminderId: String)

    @Query("DELETE FROM reminder_recipient WHERE reminderId = :reminderId")
    suspend fun deleteRecipients(reminderId: String)

    @Insert
    suspend fun insertTimes(times: List<ReminderTimeEntity>)

    @Insert
    suspend fun insertWindows(windows: List<ReminderWindowEntity>)

    @Upsert
    suspend fun upsertRecipients(recipients: List<ReminderRecipientEntity>)

    /** Replaces a reminder together with its times, windows and invites. */
    @Transaction
    suspend fun saveReminder(
        reminder: ReminderEntity,
        times: List<ReminderTimeEntity>,
        windows: List<ReminderWindowEntity>,
        recipients: List<ReminderRecipientEntity>,
    ) {
        upsertReminder(reminder)
        deleteTimes(reminder.id)
        deleteWindows(reminder.id)
        deleteRecipients(reminder.id)
        insertTimes(times)
        insertWindows(windows)
        upsertRecipients(recipients)
    }

    /** Removes a reminder from this phone only (a shared reminder this member is no longer invited to). */
    @Query("DELETE FROM reminder WHERE id = :id")
    suspend fun deleteLocal(id: String)

    @Query("UPDATE reminder SET deleted = 1, updatedAt = :now, dirty = 1 WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE reminder SET active = :active, updatedAt = :now, dirty = 1 WHERE id = :id")
    suspend fun setActive(id: String, active: Boolean, now: Long)

    @Query("UPDATE reminder SET dirty = 0 WHERE id = :id")
    suspend fun clearDirty(id: String)

    @Query("SELECT * FROM reminder_state WHERE reminderId = :id AND memberId = :memberId")
    suspend fun getState(id: String, memberId: String): ReminderStateEntity?

    @Upsert
    suspend fun upsertState(state: ReminderStateEntity)

    @Upsert
    suspend fun upsertStates(states: List<ReminderStateEntity>)

    @Query("UPDATE reminder_state SET dirty = 0 WHERE reminderId = :id AND memberId = :memberId")
    suspend fun clearStateDirty(id: String, memberId: String)

    @Query("SELECT * FROM reminder_recipient WHERE reminderId = :id AND memberId = :memberId")
    suspend fun getRecipient(id: String, memberId: String): ReminderRecipientEntity?

    @Query("UPDATE reminder SET ownerMemberId = :to, updatedAt = :now, dirty = 1 WHERE ownerMemberId = :from")
    suspend fun moveReminders(from: String, to: String, now: Long)

    @Query("UPDATE reminder_state SET memberId = :to, dirty = 1 WHERE memberId = :from")
    suspend fun moveStates(from: String, to: String)

    /** Assignments are only cached for chores that exist, so a missing row covers both cases. */
    @Query(
        """
        UPDATE reminder SET deleted = 1, updatedAt = :now, dirty = 1
        WHERE choreId IS NOT NULL AND deleted = 0 AND NOT EXISTS (
            SELECT 1 FROM chore_assignment a WHERE a.choreId = reminder.choreId AND a.memberId = reminder.ownerMemberId
        )
        """,
    )
    suspend fun softDeleteUnassignedChoreReminders(now: Long): Int

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

    /** Every cached member, including removed ones (for names on their shared reminders). */
    @Query("SELECT * FROM member")
    suspend fun getAllMembers(): List<MemberEntity>

    /** Marks cached members that the server no longer lists as deleted. */
    @Query("UPDATE member SET deleted = 1 WHERE id NOT IN (:keepIds) AND id != :localId")
    suspend fun markOthersDeleted(keepIds: List<String>, localId: String = LOCAL_MEMBER_ID)

    @Query("DELETE FROM member_settings WHERE memberId = :memberId")
    suspend fun deleteSettings(memberId: String)

    @Query("DELETE FROM member WHERE id = :memberId")
    suspend fun deleteMember(memberId: String)
}
