package app.everyday.android.data

import app.everyday.android.reminders.MINUTES_PER_DAY
import app.everyday.android.reminders.ReminderSpec
import app.everyday.android.reminders.isOnWeekday
import app.everyday.android.reminders.Window
import androidx.room.withTransaction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Editable form of a chore reminder ("More reminder options"), saved in one transaction. */
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

/** Editable form of an event. Weekly on [weekdays], or once on [date]. No [startMinute] = all day. */
data class EventDraft(
    val id: String? = null,
    val title: String = "",
    val notes: String = "",
    val weekly: Boolean = true,
    val weekdays: Int = 0,
    val date: LocalDate? = null,
    val startMinute: Int? = null,
    val endMinute: Int? = null,
    /** Members it's shared with (not the owner). */
    val memberIds: List<String> = emptyList(),
    /** While personal, others see it as "Busy" (times only); false hides it from them. */
    val showBusy: Boolean = true,
)

/** How a person's persistent reminder on an event shows: not at all, all day, or from X minutes before the start. */
sealed interface Persistent {
    data object Off : Persistent
    data object AllDay : Persistent
    data class Before(val minutes: Int) : Persistent
}

/** A reminder as seen by its owner ([memberId], the picked profile), with the event it belongs to. */
data class ReminderView(val details: ReminderWithDetails, val memberId: String, val event: EventEntity? = null) {
    val reminder get() = details.reminder
    val times get() = details.times
    val windows get() = details.windows
    val state get() = details.states.firstOrNull { it.memberId == memberId }

    /** What the notification says: the event's title, or a chore reminder's own message. */
    val title get() = event?.title ?: reminder.message
}

/** An event the picked profile sees, with who it's shared with and that profile's own reminders on it. */
data class EventView(val event: EventEntity, val members: List<EventMemberEntity>, val memberId: String, val reminders: List<ReminderView>) {
    val isOwn get() = event.ownerMemberId == memberId
    val timed get() = reminders.firstOrNull { it.reminder.mode == ReminderMode.TIMED }
    val persistentReminder get() = reminders.firstOrNull { it.reminder.mode == ReminderMode.PERSISTENT }
    val fixedTimes get() = timed?.times.orEmpty().filter { !it.relative }.map { it.minuteOfDay }.sorted()
    val minutesBefore get() = timed?.times.orEmpty().filter { it.relative }.map { it.minuteOfDay }.sorted()
    val persistent: Persistent
        get() = persistentReminder?.let { p -> p.reminder.leadMinutes?.let { Persistent.Before(it) } ?: Persistent.AllDay } ?: Persistent.Off

    /** Paused if every reminder of mine on it is paused. */
    val paused get() = reminders.isNotEmpty() && reminders.none { it.reminder.active }

    fun isOn(date: LocalDate) = event.onDate?.let { it == date.toString() } ?: isOnWeekday(event.weekdays, date)
}

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
    /** Emits the picked household's id (null before joining, or before the first refresh after updating to 2.0). */
    private val householdIdChanges: Flow<String?>,
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

    /** The current profile's reminders on chores and on events it sees. */
    fun observeReminders(): Flow<List<ReminderView>> = memberIdChanges.flatMapLatest { id ->
        combine(reminders.observeVisible(id), reminders.observeVisibleEvents(id)) { list, events -> views(id, list, events) }
    }

    suspend fun getReminder(id: String) = reminders.getReminder(id)

    suspend fun getReminders(memberId: String = currentMemberId()) =
        views(memberId, reminders.getVisible(memberId), reminders.getVisibleEvents(memberId))

    private fun views(memberId: String, list: List<ReminderWithDetails>, events: List<EventWithMembers>): List<ReminderView> {
        val byId = events.associate { it.event.id to it.event }
        return list.sortedBy { it.reminder.createdAt }.map { ReminderView(it, memberId, it.reminder.eventId?.let(byId::get)) }
    }

    /** Other members' personal events the current profile sees as Busy (Phase 7). */
    fun observeBusy(): Flow<List<BusyEventEntity>> = memberIdChanges.flatMapLatest { reminders.observeBusy(it) }

    /** Events the current profile sees, with its own reminders on each. */
    fun observeEvents(): Flow<List<EventView>> = memberIdChanges.flatMapLatest { id ->
        combine(reminders.observeVisibleEvents(id), reminders.observeVisible(id)) { events, list ->
            val mine = views(id, list, events).groupBy { it.reminder.eventId }
            events.sortedBy { it.event.createdAt }.map { EventView(it.event, it.members, id, mine[it.event.id].orEmpty()) }
        }
    }

    suspend fun getEventDraft(id: String): EventDraft? = reminders.getEvent(id)?.let { e ->
        EventDraft(
            id = e.event.id,
            title = e.event.title,
            notes = e.event.notes.orEmpty(),
            weekly = e.event.onDate == null,
            weekdays = e.event.weekdays,
            date = e.event.onDate?.let(LocalDate::parse),
            startMinute = e.event.startMinute,
            endMinute = e.event.endMinute,
            memberIds = e.members.map { it.memberId },
            showBusy = e.event.showBusy,
        )
    }

    /** Saves an event the current profile owns (or creates it) and returns its id. */
    suspend fun saveEvent(draft: EventDraft): String = db.withTransaction {
        val now = clock()
        val id = draft.id ?: UUID.randomUUID().toString()
        val existing = reminders.getEvent(id)
        val owner = existing?.event?.ownerMemberId ?: currentMemberId()
        val event = EventEntity(
            id = id,
            ownerMemberId = owner,
            title = draft.title.trim(),
            notes = draft.notes.trim().ifEmpty { null },
            weekdays = if (draft.weekly) draft.weekdays else 0,
            onDate = if (draft.weekly) null else draft.date?.toString(),
            startMinute = draft.startMinute,
            endMinute = draft.startMinute?.let { draft.endMinute },
            createdAt = existing?.event?.createdAt ?: now,
            updatedAt = now,
            dirty = true,
            showBusy = draft.showBusy,
        )
        // Members keep their own "hidden"; new ones start shown.
        val previous = existing?.members.orEmpty().associateBy { it.memberId }
        val members = (draft.memberIds.distinct() - owner).map { previous[it] ?: EventMemberEntity(id, it, updatedAt = now) }
        reminders.saveEvent(event, members)
        // Relative reminders need a start time; drop them if the event became all day.
        if (event.startMinute == null) {
            for (r in reminders.getMineOnEvent(owner, id)) {
                when (r.reminder.mode) {
                    ReminderMode.TIMED -> {
                        val fixed = r.times.filter { !it.relative }
                        if (fixed.size != r.times.size) saveEventReminder(r.reminder, fixed.map { it.minuteOfDay to false }, now)
                    }
                    ReminderMode.PERSISTENT -> if (r.reminder.leadMinutes != null) reminders.softDelete(r.reminder.id, now)
                }
            }
        }
        id
    }

    /** Deletes an event the current profile owns, and its reminders. */
    suspend fun deleteEvent(id: String) = db.withTransaction {
        reminders.softDeleteEvent(id, clock())
        reminders.softDeleteMineOnEvent(id, currentMemberId(), clock())
    }

    /** The current profile leaves (or declines) a shared event: it's removed from it, with its reminders on it. */
    suspend fun leaveEvent(eventId: String) = db.withTransaction {
        val me = currentMemberId()
        val row = reminders.getEventMember(eventId, me) ?: return@withTransaction
        reminders.upsertEventMembers(listOf(row.copy(hidden = true, updatedAt = clock(), dirty = true)))
        reminders.softDeleteMineOnEvent(eventId, me, clock())
    }

    private suspend fun saveEventReminder(reminder: ReminderEntity, times: List<Pair<Int, Boolean>>, now: Long) {
        if (reminder.mode == ReminderMode.TIMED && times.isEmpty()) {
            reminders.softDelete(reminder.id, now)
            return
        }
        reminders.saveReminder(
            reminder.copy(updatedAt = now, dirty = true, deleted = false),
            times.distinct().sortedWith(compareBy({ it.second }, { it.first })).map { (minute, relative) ->
                ReminderTimeEntity(UUID.randomUUID().toString(), reminder.id, minute, relative)
            },
            emptyList(),
        )
    }

    private fun newEventReminder(eventId: String, mode: ReminderMode, now: Long) = ReminderEntity(
        id = UUID.randomUUID().toString(),
        ownerMemberId = currentMemberId(),
        message = "",
        mode = mode,
        weekdays = 0,
        createdAt = now,
        updatedAt = now,
        eventId = eventId,
    )

    /** Adds a time to the current profile's timed reminder on the event: a clock time, or [relative] minutes before the start. */
    suspend fun addEventTime(eventId: String, minute: Int, relative: Boolean) = db.withTransaction {
        val now = clock()
        val existing = reminders.getMineOnEvent(currentMemberId(), eventId).firstOrNull { it.reminder.mode == ReminderMode.TIMED }
        val reminder = existing?.reminder ?: newEventReminder(eventId, ReminderMode.TIMED, now)
        val times = existing?.times.orEmpty().map { it.minuteOfDay to it.relative }
        saveEventReminder(reminder, times + (minute to relative), now)
    }

    /** Removes a time; a timed reminder left with no times is deleted. */
    suspend fun removeEventTime(eventId: String, minute: Int, relative: Boolean) = db.withTransaction {
        val existing = reminders.getMineOnEvent(currentMemberId(), eventId).firstOrNull { it.reminder.mode == ReminderMode.TIMED }
            ?: return@withTransaction
        saveEventReminder(existing.reminder, existing.times.map { it.minuteOfDay to it.relative } - (minute to relative), clock())
    }

    /** Sets how the current profile's persistent reminder on the event shows, creating or deleting it. */
    suspend fun setPersistent(eventId: String, persistent: Persistent) = db.withTransaction {
        val now = clock()
        val existing = reminders.getMineOnEvent(currentMemberId(), eventId).firstOrNull { it.reminder.mode == ReminderMode.PERSISTENT }
        when (persistent) {
            Persistent.Off -> existing?.let { reminders.softDelete(it.reminder.id, now) }
            else -> {
                val lead = (persistent as? Persistent.Before)?.minutes
                val reminder = existing?.reminder ?: newEventReminder(eventId, ReminderMode.PERSISTENT, now)
                saveEventReminder(reminder.copy(leadMinutes = lead), emptyList(), now)
            }
        }
    }

    /** Pauses or resumes all of the current profile's reminders on an event. */
    suspend fun setEventActive(eventId: String, active: Boolean) = db.withTransaction {
        reminders.getMineOnEvent(currentMemberId(), eventId).forEach { reminders.setActive(it.reminder.id, active, clock()) }
    }

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

    /** Saves a chore reminder draft and returns its id. Times or windows not used by the mode are dropped. */
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
            dirty = true,
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

    private suspend fun myTimedChoreReminders(choreId: String) = getReminders().filter {
        it.reminder.choreId == choreId && it.reminder.mode == ReminderMode.TIMED && it.reminder.active
    }.sortedBy { it.reminder.createdAt }

    /**
     * Quick chore time: adds [minute] to the current profile's first timed reminder for the chore,
     * or creates one with the chore's title and days.
     */
    suspend fun addChoreTime(choreId: String, title: String, weekdays: Int, minute: Int) {
        val existing = myTimedChoreReminders(choreId).firstOrNull()?.let { getDraft(it.reminder.id) }
        if (existing == null) {
            save(ReminderDraft(message = title, mode = ReminderMode.TIMED, weekdays = weekdays, times = listOf(minute), choreId = choreId))
        } else if (minute !in existing.times) {
            save(existing.copy(times = (existing.times + minute).sorted()))
        }
    }

    /** Removes [minute] from the current profile's timed reminders for the chore; one left with no times is deleted. */
    suspend fun removeChoreTime(choreId: String, minute: Int) {
        for (r in myTimedChoreReminders(choreId)) {
            val draft = getDraft(r.reminder.id) ?: continue
            if (minute !in draft.times) continue
            val left = draft.times - minute
            if (left.isEmpty()) delete(r.reminder.id) else save(draft.copy(times = left))
        }
    }

    /** Deletes chore reminders whose owner is no longer assigned to the chore, or whose chore is gone. */
    suspend fun deleteUnassignedChoreReminders() = reminders.softDeleteUnassignedChoreReminders(clock())

    suspend fun setActive(id: String, active: Boolean) = reminders.setActive(id, active, clock())

    /** Done today, for [memberId]: the reminder's owner (the picked profile by default). */
    suspend fun setDone(id: String, date: LocalDate?, memberId: String? = null) {
        val me = memberId ?: currentMemberId()
        val state = reminders.getState(id, me) ?: ReminderStateEntity(id, me)
        reminders.upsertState(state.copy(doneOn = date?.toString(), updatedAt = clock(), dirty = true))
    }

    suspend fun setSnooze(id: String, until: Instant?, memberId: String? = null) {
        val me = memberId ?: currentMemberId()
        val state = reminders.getState(id, me) ?: ReminderStateEntity(id, me)
        reminders.upsertState(state.copy(snoozeUntil = until?.toEpochMilli(), updatedAt = clock(), dirty = true))
    }

    fun observeSettings(): Flow<MemberSettingsEntity?> = memberIdChanges.flatMapLatest { members.observeSettings(it) }

    suspend fun getSettings(memberId: String = currentMemberId()): MemberSettingsEntity =
        members.getSettings(memberId) ?: MemberSettingsEntity(memberId)

    suspend fun saveSettings(settings: MemberSettingsEntity) =
        members.upsertSettings(settings.copy(updatedAt = clock(), dirty = true))

    /** The picked household's members. */
    fun observeMembers(): Flow<List<MemberEntity>> = householdIdChanges.flatMapLatest { members.observeMembers(it) }

    /** Replaces one household's cached members with the server's list. */
    suspend fun cacheMembers(householdId: String, list: List<MemberEntity>) = db.withTransaction {
        members.upsertMembers(list.map { it.copy(updatedAt = clock(), householdId = householdId) })
        members.markOthersDeleted(householdId, list.map { it.id })
    }

    /** Gives the reminders and settings made before joining to [memberId], then removes the local profile. */
    suspend fun adoptLocalProfile(memberId: String) = db.withTransaction {
        reminders.moveEvents(LOCAL_MEMBER_ID, memberId, clock())
        reminders.moveReminders(LOCAL_MEMBER_ID, memberId, clock())
        reminders.moveStates(LOCAL_MEMBER_ID, memberId)
        val local = members.getSettings(LOCAL_MEMBER_ID)
        if (local != null && members.getSettings(memberId) == null) {
            members.upsertSettings(local.copy(memberId = memberId, updatedAt = clock(), dirty = true))
        }
        members.deleteSettings(LOCAL_MEMBER_ID)
        members.deleteMember(LOCAL_MEMBER_ID)
    }
}

fun ReminderView.toSpec(choreDoneOn: Set<LocalDate> = emptySet()): ReminderSpec {
    val e = event
    return ReminderSpec(
        id = reminder.id,
        mode = reminder.mode,
        weekdays = e?.weekdays ?: reminder.weekdays,
        onDate = e?.onDate?.let(LocalDate::parse),
        active = reminder.active,
        times = times.filter { !it.relative }.map { it.minuteOfDay },
        minutesBefore = times.filter { it.relative }.map { it.minuteOfDay },
        windows = when {
            e == null -> windows.map { Window(it.startMinute, it.endMinute) }
            reminder.mode == ReminderMode.PERSISTENT && reminder.leadMinutes == null -> listOf(Window(0, MINUTES_PER_DAY))
            else -> emptyList()
        },
        eventStart = e?.startMinute,
        eventEnd = e?.endMinute,
        leadMinutes = if (reminder.mode == ReminderMode.PERSISTENT) reminder.leadMinutes else null,
        doneOn = state?.doneOn?.let(LocalDate::parse),
        snoozeUntil = state?.snoozeUntil?.let(Instant::ofEpochMilli),
        choreDoneOn = choreDoneOn,
    )
}
