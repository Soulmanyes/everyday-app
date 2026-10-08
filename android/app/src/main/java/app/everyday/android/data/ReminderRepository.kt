package app.everyday.android.data

import app.everyday.android.reminders.ReminderSpec
import app.everyday.android.reminders.Window
import androidx.room.withTransaction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Editable form of a reminder, used by the editor and saved in one transaction. */
data class ReminderDraft(
    val id: String? = null,
    val message: String = "",
    val mode: ReminderMode = ReminderMode.TIMED,
    val weekdays: Int = 0b1111111,
    val active: Boolean = true,
    val times: List<Int> = emptyList(),
    val windows: List<Window> = emptyList(),
    /** Set for a chore reminder; fixed once the reminder exists. */
    val choreId: String? = null,
)

/**
 * Reminders and settings of the profile currently picked on this phone ([currentMemberId]): the
 * household member, or [LOCAL_MEMBER_ID] when the phone hasn't joined a household.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReminderRepository(
    private val db: AppDatabase,
    private val currentMemberId: () -> String,
    /** Emits the current profile id whenever it changes, so observed data follows profile switches. */
    private val memberIdChanges: Flow<String>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val reminders = db.reminderDao()
    private val members = db.memberDao()

    /** Makes sure the current profile has a member row (for the local profile) and settings. */
    suspend fun ensureCurrentMember() {
        val id = currentMemberId()
        if (id == LOCAL_MEMBER_ID) members.insertIfMissing(MemberEntity(LOCAL_MEMBER_ID, "Me", clock()))
        members.insertSettingsIfMissing(MemberSettingsEntity(id, updatedAt = clock()))
    }

    fun observeReminders(): Flow<List<ReminderWithDetails>> = memberIdChanges.flatMapLatest { id ->
        reminders.observeReminders(id).map { list -> list.sortedBy { it.reminder.createdAt } }
    }

    suspend fun getReminders(memberId: String = currentMemberId()) = reminders.getReminders(memberId)

    suspend fun getDraft(id: String): ReminderDraft? = reminders.getReminder(id)?.let { d ->
        ReminderDraft(
            id = d.reminder.id,
            message = d.reminder.message,
            mode = d.reminder.mode,
            weekdays = d.reminder.weekdays,
            active = d.reminder.active,
            times = d.times.map { it.minuteOfDay }.sorted(),
            windows = d.windows.map { Window(it.startMinute, it.endMinute) }.sortedBy { it.startMinute },
            choreId = d.reminder.choreId,
        )
    }

    /** Saves a draft and returns its id. Times or windows not used by the mode are dropped. */
    suspend fun save(draft: ReminderDraft, ownerMemberId: String = currentMemberId()): String {
        val now = clock()
        val id = draft.id ?: UUID.randomUUID().toString()
        val existing = draft.id?.let { reminders.getReminder(it)?.reminder }
        val entity = ReminderEntity(
            id = id,
            ownerMemberId = existing?.ownerMemberId ?: ownerMemberId,
            message = draft.message.trim(),
            mode = draft.mode,
            weekdays = draft.weekdays,
            active = draft.active,
            choreId = existing?.choreId ?: draft.choreId,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        val times = if (draft.mode == ReminderMode.TIMED) draft.times.distinct() else emptyList()
        val windows = if (draft.mode == ReminderMode.PERSISTENT) draft.windows else emptyList()
        reminders.saveReminder(
            entity,
            times.map { ReminderTimeEntity(UUID.randomUUID().toString(), id, it) },
            windows.map { ReminderWindowEntity(UUID.randomUUID().toString(), id, it.startMinute, it.endMinute) },
        )
        return id
    }

    suspend fun delete(id: String) = reminders.softDelete(id, clock())

    /** Deletes chore reminders whose owner is no longer assigned to the chore, or whose chore is gone. */
    suspend fun deleteUnassignedChoreReminders() = reminders.softDeleteUnassignedChoreReminders(clock())

    suspend fun setActive(id: String, active: Boolean) = reminders.setActive(id, active, clock())

    suspend fun setDone(id: String, date: LocalDate?) {
        val state = reminders.getState(id) ?: ReminderStateEntity(id)
        reminders.upsertState(state.copy(doneOn = date?.toString(), updatedAt = clock()))
    }

    suspend fun setSnooze(id: String, until: Instant?) {
        val state = reminders.getState(id) ?: ReminderStateEntity(id)
        reminders.upsertState(state.copy(snoozeUntil = until?.toEpochMilli(), updatedAt = clock()))
    }

    fun observeSettings(): Flow<MemberSettingsEntity?> = memberIdChanges.flatMapLatest { members.observeSettings(it) }

    suspend fun getSettings(memberId: String = currentMemberId()): MemberSettingsEntity =
        members.getSettings(memberId) ?: MemberSettingsEntity(memberId)

    suspend fun saveSettings(settings: MemberSettingsEntity) =
        members.upsertSettings(settings.copy(updatedAt = clock()))

    fun observeMembers() = members.observeMembers()

    /** Replaces the cached household members with the server's list. */
    suspend fun cacheMembers(list: List<Pair<String, String>>) = db.withTransaction {
        members.upsertMembers(list.map { (id, name) -> MemberEntity(id, name, clock()) })
        members.markOthersDeleted(list.map { it.first })
    }

    /** Gives the reminders and settings made before joining to [memberId], then removes the local profile. */
    suspend fun adoptLocalProfile(memberId: String) = db.withTransaction {
        reminders.moveReminders(LOCAL_MEMBER_ID, memberId, clock())
        val local = members.getSettings(LOCAL_MEMBER_ID)
        if (local != null && members.getSettings(memberId) == null) {
            members.upsertSettings(local.copy(memberId = memberId, updatedAt = clock()))
        }
        members.deleteSettings(LOCAL_MEMBER_ID)
        members.deleteMember(LOCAL_MEMBER_ID)
    }
}

fun ReminderWithDetails.toSpec(choreDoneOn: Set<LocalDate> = emptySet()) = ReminderSpec(
    id = reminder.id,
    mode = reminder.mode,
    weekdays = reminder.weekdays,
    active = reminder.active,
    times = times.map { it.minuteOfDay },
    windows = windows.map { Window(it.startMinute, it.endMinute) },
    doneOn = state?.doneOn?.let(LocalDate::parse),
    snoozeUntil = state?.snoozeUntil?.let(Instant::ofEpochMilli),
    choreDoneOn = choreDoneOn,
)
