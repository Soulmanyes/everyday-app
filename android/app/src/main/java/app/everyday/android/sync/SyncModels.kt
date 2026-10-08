package app.everyday.android.sync

import app.everyday.android.data.MemberSettingsEntity
import app.everyday.android.data.RecipientStatus
import app.everyday.android.data.ReminderEntity
import app.everyday.android.data.ReminderRecipientEntity
import app.everyday.android.data.ReminderStateEntity
import app.everyday.android.data.ReminderTimeEntity
import app.everyday.android.data.ReminderWindowEntity
import app.everyday.android.data.ReminderWithDetails
import app.everyday.android.reminders.Window
import java.util.UUID

/** A reminder as the server sends it: the row with its times, windows and invites. */
data class SyncedReminder(
    val reminder: ReminderEntity,
    val times: List<Int>,
    val windows: List<Window>,
    /** Invites, keyed by member id. */
    val recipients: List<ReminderRecipientEntity>,
)

/** The server's answer to a sync: everything the member can see, and the pushed ids it refused. */
data class SyncSnapshot(
    val reminders: List<SyncedReminder>,
    val states: List<ReminderStateEntity>,
    val settings: MemberSettingsEntity?,
    /** Reminder ids, "state:<reminderId>", "recipient:<reminderId>" or "settings". */
    val rejected: Set<String>,
)

/** The updatedAt of each row as it was pushed, to tell whether it changed again during the sync. */
data class Pushed(
    val reminders: Map<String, Long> = emptyMap(),
    val states: Map<String, Long> = emptyMap(),
    val answers: Map<String, Long> = emptyMap(),
    val settings: Long? = null,
)

fun SyncedReminder.timeEntities() = times.map { ReminderTimeEntity(UUID.randomUUID().toString(), reminder.id, it) }

fun SyncedReminder.windowEntities() =
    windows.map { ReminderWindowEntity(UUID.randomUUID().toString(), reminder.id, it.startMinute, it.endMinute) }

fun ReminderWithDetails.toSynced() = SyncedReminder(
    reminder = reminder,
    times = times.map { it.minuteOfDay },
    windows = windows.map { Window(it.startMinute, it.endMinute) },
    recipients = recipients,
)

fun RecipientStatus.wire() = name.lowercase()

fun recipientStatus(wire: String) = RecipientStatus.valueOf(wire.uppercase())
