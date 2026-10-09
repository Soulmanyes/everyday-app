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

/** Events [memberId] sees: their own, and shared ones they haven't hidden. */
private const val VISIBLE_EVENTS = """
    SELECT * FROM event WHERE deleted = 0 AND (ownerMemberId = :memberId
    OR id IN (SELECT eventId FROM event_member WHERE memberId = :memberId AND hidden = 0))
"""

/** [memberId]'s own reminders on chores, and on events they see. */
private const val VISIBLE = """
    SELECT * FROM reminder WHERE deleted = 0 AND ownerMemberId = :memberId
    AND (choreId IS NOT NULL OR eventId IN (SELECT id FROM ($VISIBLE_EVENTS)))
"""

data class EventWithMembers(
    @Embedded val event: EventEntity,
    @Relation(parentColumn = "id", entityColumn = "eventId")
    val members: List<EventMemberEntity>,
)

data class ReminderWithDetails(
    @Embedded val reminder: ReminderEntity,
    @Relation(parentColumn = "id", entityColumn = "reminderId")
    val times: List<ReminderTimeEntity>,
    @Relation(parentColumn = "id", entityColumn = "reminderId")
    val windows: List<ReminderWindowEntity>,
    /** Done/snooze state, one row per member (normally only the owner's). */
    @Relation(parentColumn = "id", entityColumn = "reminderId")
    val states: List<ReminderStateEntity>,
)

@Dao
interface ReminderDao {
    /** [memberId]'s reminders on chores and on events they see. */
    @Transaction
    @Query(VISIBLE)
    fun observeVisible(memberId: String): Flow<List<ReminderWithDetails>>

    @Transaction
    @Query(VISIBLE)
    suspend fun getVisible(memberId: String): List<ReminderWithDetails>

    /** Every reminder on this phone, with tombstones, for sync to compare. */
    @Transaction
    @Query("SELECT * FROM reminder")
    suspend fun getForSync(): List<ReminderWithDetails>

    @Transaction
    @Query("SELECT * FROM reminder WHERE ownerMemberId = :memberId AND dirty = 1")
    suspend fun getDirtyOwned(memberId: String): List<ReminderWithDetails>

    @Query("SELECT * FROM reminder_state WHERE memberId = :memberId AND dirty = 1")
    suspend fun getDirtyStates(memberId: String): List<ReminderStateEntity>

    @Query("SELECT * FROM reminder_state WHERE memberId = :memberId")
    suspend fun getStates(memberId: String): List<ReminderStateEntity>

    @Transaction
    @Query("SELECT * FROM reminder WHERE id = :id")
    suspend fun getReminder(id: String): ReminderWithDetails?

    @Transaction
    @Query("SELECT * FROM reminder WHERE deleted = 0 AND ownerMemberId = :memberId AND eventId = :eventId")
    suspend fun getMineOnEvent(memberId: String, eventId: String): List<ReminderWithDetails>

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
    suspend fun saveReminder(reminder: ReminderEntity, times: List<ReminderTimeEntity>, windows: List<ReminderWindowEntity>) {
        upsertReminder(reminder)
        deleteTimes(reminder.id)
        deleteWindows(reminder.id)
        insertTimes(times)
        insertWindows(windows)
    }

    /** Removes a reminder from this phone only (not this member's, or its event is gone). */
    @Query("DELETE FROM reminder WHERE id = :id")
    suspend fun deleteLocal(id: String)

    @Query("UPDATE reminder SET deleted = 1, updatedAt = :now, dirty = 1 WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE reminder SET deleted = 1, updatedAt = :now, dirty = 1 WHERE eventId = :eventId AND ownerMemberId = :memberId AND deleted = 0")
    suspend fun softDeleteMineOnEvent(eventId: String, memberId: String, now: Long)

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

    // Events

    @Transaction
    @Query(VISIBLE_EVENTS)
    fun observeVisibleEvents(memberId: String): Flow<List<EventWithMembers>>

    @Transaction
    @Query(VISIBLE_EVENTS)
    suspend fun getVisibleEvents(memberId: String): List<EventWithMembers>

    /** Every event on this phone, with tombstones, for sync to compare. */
    @Transaction
    @Query("SELECT * FROM event")
    suspend fun getEventsForSync(): List<EventWithMembers>

    @Transaction
    @Query("SELECT * FROM event WHERE ownerMemberId = :memberId AND dirty = 1")
    suspend fun getDirtyOwnedEvents(memberId: String): List<EventWithMembers>

    /** [memberId]'s own hide/show changes on shared events. */
    @Query("SELECT * FROM event_member WHERE memberId = :memberId AND dirty = 1")
    suspend fun getDirtyHidden(memberId: String): List<EventMemberEntity>

    @Transaction
    @Query("SELECT * FROM event WHERE id = :id")
    suspend fun getEvent(id: String): EventWithMembers?

    @Upsert
    suspend fun upsertEvent(event: EventEntity)

    @Query("DELETE FROM event_member WHERE eventId = :eventId")
    suspend fun deleteEventMembers(eventId: String)

    @Upsert
    suspend fun upsertEventMembers(members: List<EventMemberEntity>)

    /** Replaces an event together with who it's shared with. */
    @Transaction
    suspend fun saveEvent(event: EventEntity, members: List<EventMemberEntity>) {
        upsertEvent(event)
        deleteEventMembers(event.id)
        upsertEventMembers(members)
    }

    @Query("UPDATE event SET deleted = 1, updatedAt = :now, dirty = 1 WHERE id = :id")
    suspend fun softDeleteEvent(id: String, now: Long)

    /** Removes an event from this phone only (no longer shared with this member). */
    @Query("DELETE FROM event WHERE id = :id")
    suspend fun deleteEventLocal(id: String)

    @Query("UPDATE event SET dirty = 0 WHERE id = :id")
    suspend fun clearEventDirty(id: String)

    @Query("SELECT * FROM event_member WHERE eventId = :eventId AND memberId = :memberId")
    suspend fun getEventMember(eventId: String, memberId: String): EventMemberEntity?

    @Query("UPDATE event SET ownerMemberId = :to, updatedAt = :now, dirty = 1 WHERE ownerMemberId = :from")
    suspend fun moveEvents(from: String, to: String, now: Long)
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
