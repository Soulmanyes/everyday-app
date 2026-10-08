package app.everyday.android.data

import app.everyday.android.reminders.ReminderSpec
import app.everyday.android.reminders.Window
import kotlinx.coroutines.flow.Flow
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
)

class ReminderRepository(
    private val db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val reminders = db.reminderDao()
    private val members = db.memberDao()

    /** Makes sure the Phase 1 local member and its settings exist. */
    suspend fun ensureLocalMember() {
        members.insertIfMissing(MemberEntity(LOCAL_MEMBER_ID, "Me", clock()))
        members.insertSettingsIfMissing(MemberSettingsEntity(LOCAL_MEMBER_ID, updatedAt = clock()))
    }

    fun observeReminders(memberId: String = LOCAL_MEMBER_ID): Flow<List<ReminderWithDetails>> =
        reminders.observeReminders(memberId).map { list -> list.sortedBy { it.reminder.createdAt } }

    suspend fun getReminders(memberId: String = LOCAL_MEMBER_ID) = reminders.getReminders(memberId)

    suspend fun getDraft(id: String): ReminderDraft? = reminders.getReminder(id)?.let { d ->
        ReminderDraft(
            id = d.reminder.id,
            message = d.reminder.message,
            mode = d.reminder.mode,
            weekdays = d.reminder.weekdays,
            active = d.reminder.active,
            times = d.times.map { it.minuteOfDay }.sorted(),
            windows = d.windows.map { Window(it.startMinute, it.endMinute) }.sortedBy { it.startMinute },
        )
    }

    /** Saves a draft and returns its id. Times or windows not used by the mode are dropped. */
    suspend fun save(draft: ReminderDraft, ownerMemberId: String = LOCAL_MEMBER_ID): String {
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
            choreId = existing?.choreId,
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

    suspend fun setActive(id: String, active: Boolean) = reminders.setActive(id, active, clock())

    suspend fun setDone(id: String, date: LocalDate?) {
        val state = reminders.getState(id) ?: ReminderStateEntity(id)
        reminders.upsertState(state.copy(doneOn = date?.toString(), updatedAt = clock()))
    }

    suspend fun setSnooze(id: String, until: Instant?) {
        val state = reminders.getState(id) ?: ReminderStateEntity(id)
        reminders.upsertState(state.copy(snoozeUntil = until?.toEpochMilli(), updatedAt = clock()))
    }

    fun observeSettings(memberId: String = LOCAL_MEMBER_ID) = members.observeSettings(memberId)

    suspend fun getSettings(memberId: String = LOCAL_MEMBER_ID): MemberSettingsEntity =
        members.getSettings(memberId) ?: MemberSettingsEntity(memberId)

    suspend fun saveSettings(settings: MemberSettingsEntity) =
        members.upsertSettings(settings.copy(updatedAt = clock()))
}

fun ReminderWithDetails.toSpec() = ReminderSpec(
    id = reminder.id,
    mode = reminder.mode,
    weekdays = reminder.weekdays,
    active = reminder.active,
    times = times.map { it.minuteOfDay },
    windows = windows.map { Window(it.startMinute, it.endMinute) },
    doneOn = state?.doneOn?.let(LocalDate::parse),
    snoozeUntil = state?.snoozeUntil?.let(Instant::ofEpochMilli),
)
