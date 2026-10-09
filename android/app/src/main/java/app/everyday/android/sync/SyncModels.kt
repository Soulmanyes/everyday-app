package app.everyday.android.sync

import app.everyday.android.data.EventEntity
import app.everyday.android.data.EventMemberEntity
import app.everyday.android.data.EventWithMembers
import app.everyday.android.data.MemberSettingsEntity
import app.everyday.android.data.ReminderEntity
import app.everyday.android.data.ReminderStateEntity
import app.everyday.android.data.ReminderTimeEntity
import app.everyday.android.data.ReminderWindowEntity
import app.everyday.android.data.ReminderWithDetails
import app.everyday.android.reminders.Window
import java.util.UUID

/** A reminder time as synced: a minute of the day, or with [relative] the minutes before the event starts. */
data class SyncedTime(val minute: Int, val relative: Boolean = false)

/** A reminder as the server sends it: the row with its times and windows. */
data class SyncedReminder(
    val reminder: ReminderEntity,
    val times: List<SyncedTime>,
    val windows: List<Window>,
)

/** An event as the server sends it, with who it's shared with. */
data class SyncedEvent(val event: EventEntity, val members: List<EventMemberEntity>)

/** The server's answer to a sync: everything the member can see, and the pushed ids it refused. */
data class SyncSnapshot(
    val events: List<SyncedEvent>,
    val reminders: List<SyncedReminder>,
    val states: List<ReminderStateEntity>,
    val settings: MemberSettingsEntity?,
    /** Reminder ids, "event:<id>", "hidden:<eventId>", "state:<reminderId>" or "settings". */
    val rejected: Set<String>,
)

/** The updatedAt of each row as it was pushed, to tell whether it changed again during the sync. */
data class Pushed(
    val events: Map<String, Long> = emptyMap(),
    val hidden: Map<String, Long> = emptyMap(),
    val reminders: Map<String, Long> = emptyMap(),
    val states: Map<String, Long> = emptyMap(),
    val settings: Long? = null,
)

fun SyncedReminder.timeEntities() = times.map { ReminderTimeEntity(UUID.randomUUID().toString(), reminder.id, it.minute, it.relative) }

fun SyncedReminder.windowEntities() =
    windows.map { ReminderWindowEntity(UUID.randomUUID().toString(), reminder.id, it.startMinute, it.endMinute) }

fun ReminderWithDetails.toSynced() = SyncedReminder(
    reminder = reminder,
    times = times.map { SyncedTime(it.minuteOfDay, it.relative) },
    windows = windows.map { Window(it.startMinute, it.endMinute) },
)

fun EventWithMembers.toSynced() = SyncedEvent(event, members)
